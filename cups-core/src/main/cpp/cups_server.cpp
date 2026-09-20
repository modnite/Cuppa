#include "cups_server.h"
#include <algorithm>
#include <ctime>
#include <fstream>
#include <chrono>
#include <sstream>
#include <sys/stat.h>
#include <cerrno>
#include <android/log.h>

#define LOG_TAG "CuppaServer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

namespace cuppa {

CupsServer& CupsServer::getInstance() {
    static CupsServer instance;
    return instance;
}

CupsServer::CupsServer() {
    // Empty constructor for production. Printers will be added dynamically via discovery.
}

bool CupsServer::start(int port, const std::string &spoolDir, const std::string &configDir) {
    std::lock_guard<std::mutex> lock(mMutex);
    mPort = port;
    mSpoolDir = spoolDir;
    mConfigDir = configDir;
    // Nothing else creates the spool directory. Without it every incoming job was accepted and
    // then silently lost: the spool write failed, the job had no file, and it was marked failed.
    for (size_t i = 1; i <= spoolDir.size(); i++) {
        if (i == spoolDir.size() || spoolDir[i] == '/') {
            std::string part = spoolDir.substr(0, i);
            if (!part.empty() && mkdir(part.c_str(), 0700) != 0 && errno != EEXIST) {
                LOGE("Could not create spool directory %s (errno=%d)", part.c_str(), errno);
                break;
            }
        }
    }
    mRunning = true;
    LOGI("CUPS server initialized: port=%d, spool=%s, config=%s", port, spoolDir.c_str(), configDir.c_str());
    return true;
}

void CupsServer::stop() {
    std::lock_guard<std::mutex> lock(mMutex);
    mRunning = false;
    LOGI("CUPS server stopped");
}

bool CupsServer::isRunning() const {
    return mRunning;
}

bool CupsServer::addPrinter(const PrinterInfo &info) {
    std::lock_guard<std::mutex> lock(mMutex);
    if (info.isDefault) {
        for (auto &p : mPrinters) {
            p.second.isDefault = false;
        }
    }
    mPrinters[info.name] = info;
    LOGI("Added printer '%s' (%s)", info.name.c_str(), info.makeAndModel.c_str());
    return true;
}

bool CupsServer::removePrinter(const std::string &name) {
    std::lock_guard<std::mutex> lock(mMutex);
    auto it = mPrinters.find(name);
    if (it != mPrinters.end()) {
        mPrinters.erase(it);
        LOGI("Removed printer '%s'", name.c_str());
        return true;
    }
    return false;
}

void CupsServer::clearPrinters() {
    std::lock_guard<std::mutex> lock(mMutex);
    mPrinters.clear();
    LOGI("Cleared all printers in native CUPS server");
}

std::vector<PrinterInfo> CupsServer::getPrinters() const {
    std::lock_guard<std::mutex> lock(mMutex);
    std::vector<PrinterInfo> list;
    list.reserve(mPrinters.size());
    for (const auto &p : mPrinters) {
        list.push_back(p.second);
    }
    return list;
}

bool CupsServer::getPrinter(const std::string &name, PrinterInfo &out) const {
    std::lock_guard<std::mutex> lock(mMutex);
    auto it = mPrinters.find(name);
    if (it != mPrinters.end()) {
        out = it->second;
        return true;
    }
    for (const auto &p : mPrinters) {
        if (p.second.uri == name || p.second.name == name) {
            out = p.second;
            return true;
        }
    }
    return false;
}

std::string CupsServer::getDefaultPrinterName() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return getDefaultPrinterNameLocked();
}

std::string CupsServer::getDefaultPrinterNameLocked() const {
    for (const auto &p : mPrinters) {
        if (p.second.isDefault) return p.first;
    }
    if (!mPrinters.empty()) return mPrinters.begin()->first;
    return "";
}

std::vector<PrintJob> CupsServer::getJobs(const std::string &printerName) const {
    std::lock_guard<std::mutex> lock(mMutex);
    if (printerName.empty()) {
        return mJobs;
    }
    std::vector<PrintJob> filtered;
    for (const auto &j : mJobs) {
        if (j.printerName == printerName) {
            filtered.push_back(j);
        }
    }
    return filtered;
}

bool CupsServer::cancelJob(int32_t jobId) {
    std::lock_guard<std::mutex> lock(mMutex);
    for (auto &j : mJobs) {
        if (j.jobId == jobId) {
            j.state = 7; // Canceled
            LOGI("Print job #%d canceled", jobId);
            return true;
        }
    }
    return false;
}

bool CupsServer::updateJobState(int32_t jobId, int32_t state) {
    std::lock_guard<std::mutex> lock(mMutex);
    for (auto &j : mJobs) {
        if (j.jobId == jobId) {
            j.state = state;
            LOGI("Print job #%d state updated to %d", jobId, state);
            return true;
        }
    }
    return false;
}

std::vector<uint8_t> CupsServer::processIppRequest(const uint8_t *data, size_t size) {
    auto req = IppMessage::parse(data, size);
    if (!req) {
        LOGE("Failed to parse incoming IPP request");
        IppMessage errResp;
        errResp.version = 0x0101;
        errResp.status = IppStatus::CLIENT_BAD_REQUEST;
        errResp.requestId = 1;
        return errResp.encode();
    }

    LOGI("Processing IPP request: op=0x%04x, requestId=%u, docSize=%zu",
         req->code, req->requestId, req->documentData.size());

    std::shared_ptr<IppMessage> resp;
    switch (static_cast<IppOp>(req->code)) {
        case IppOp::GET_PRINTER_ATTRIBUTES:
            resp = handleGetPrinterAttributes(*req);
            break;
        case IppOp::VALIDATE_JOB:
            resp = handleValidateJob(*req);
            break;
        case IppOp::PRINT_JOB:
            resp = handlePrintJob(*req);
            break;
        case IppOp::CREATE_JOB:
            resp = handleCreateJob(*req);
            break;
        case IppOp::SEND_DOCUMENT:
            resp = handleSendDocument(*req);
            break;
        case IppOp::GET_JOB_ATTRIBUTES:
            resp = handleGetJobAttributes(*req);
            break;
        case IppOp::GET_JOBS:
            resp = handleGetJobs(*req);
            break;
        case IppOp::CANCEL_JOB:
            resp = handleCancelJob(*req);
            break;
        case IppOp::CUPS_GET_PRINTERS:
            resp = handleCupsGetPrinters(*req);
            break;
        default:
            LOGI("Unsupported IPP operation: 0x%04x", req->code);
            resp = std::make_shared<IppMessage>();
            resp->version = req->version;
            resp->status = IppStatus::SERVER_OPERATION_NOT_SUPPORTED;
            resp->requestId = req->requestId;
            resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
            resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
            break;
    }

    return resp->encode();
}

static std::string extractPrinterNameFromUri(const std::string &uri) {
    if (uri.empty()) return "";
    size_t idx = uri.rfind('/');
    if (idx != std::string::npos && idx + 1 < uri.size()) {
        return uri.substr(idx + 1);
    }
    return uri;
}

static std::string percentDecode(const std::string &in) {
    std::string out;
    out.reserve(in.size());
    for (size_t i = 0; i < in.size(); i++) {
        if (in[i] == '%' && i + 2 < in.size() && isxdigit((unsigned char)in[i + 1]) && isxdigit((unsigned char)in[i + 2])) {
            out.push_back(static_cast<char>(std::stoi(in.substr(i + 1, 2), nullptr, 16)));
            i += 2;
        } else {
            out.push_back(in[i]);
        }
    }
    return out;
}

// URI-safe form of a printer name: anything outside [A-Za-z0-9._-] becomes '_' (runs collapsed).
// MUST stay identical to PrinterNaming.resourceName in the Kotlin layer, which uses it for the
// mDNS "rp" TXT record and the share URI shown in the UI. Printer names routinely contain
// spaces/parentheses/colons ("USB Printer (0x09C5:0x0588)"), and a URI containing them is invalid:
// CUPS/macOS reject the whole Get-Printer-Attributes response as bad-request.
// Job-template attributes the dispatcher passes on to the real printer. Enums arrive as 4-byte
// integers and everything else here is a keyword string. Anything not listed is dropped rather
// than forwarded blindly, because a printer may reject a job over an attribute it does not know.
static std::string collectJobOptions(const IppMessage &req) {
    static const char *kEnumOptions[] = {"print-quality", "orientation-requested"};
    static const char *kKeywordOptions[] = {"sides", "print-color-mode", "media", "print-scaling"};
    std::string out;
    for (const char *name : kEnumOptions) {
        if (const auto *a = req.findAttribute(name)) {
            if (a->valueBytes.size() == 4) out += std::string(name) + "=" + std::to_string(a->asInt()) + "\n";
        }
    }
    for (const char *name : kKeywordOptions) {
        if (const auto *a = req.findAttribute(name)) {
            std::string v = a->asString();
            if (!v.empty() && v.find('\n') == std::string::npos) out += std::string(name) + "=" + v + "\n";
        }
    }
    return out;
}

static std::string sanitizeResourceName(const std::string &name) {
    std::string out;
    out.reserve(name.size());
    for (unsigned char c : name) {
        if (isalnum(c) || c == '.' || c == '-' || c == '_') {
            out.push_back(static_cast<char>(c));
        } else if (!out.empty() && out.back() != '_') {
            out.push_back('_');
        }
    }
    while (!out.empty() && out.back() == '_') out.pop_back();
    return out.empty() ? "printer" : out;
}

std::string CupsServer::resolvePrinterNameLocked(const std::string &uriSegment) const {
    if (uriSegment.empty()) return "";
    std::string decoded = percentDecode(uriSegment);
    if (mPrinters.find(decoded) != mPrinters.end()) return decoded;
    std::string want = sanitizeResourceName(decoded);
    for (const auto &p : mPrinters) {
        // Case-insensitive: Windows lowercases the queue path before it asks for it.
        if (strcasecmp(sanitizeResourceName(p.first).c_str(), want.c_str()) == 0 || strcasecmp(p.first.c_str(), decoded.c_str()) == 0) {
            return p.first;
        }
    }
    return "";
}

int32_t CupsServer::queuedJobCountLocked(const std::string &printerName) const {
    int32_t n = 0;
    for (const auto &j : mJobs) {
        if (j.printerName == printerName && (j.state == 3 || j.state == 4 || j.state == 5)) n++;
    }
    return n;
}

std::string CupsServer::hostPortForRequest(const IppMessage &req) const {
    std::string uri = req.getPrinterUri();
    if (uri.empty()) {
        const auto *ju = req.findAttribute("job-uri");
        if (ju) uri = ju->asString();
    }
    size_t schemePos = uri.find("://");
    if (schemePos != std::string::npos) {
        size_t hostStart = schemePos + 3;
        size_t pathStart = uri.find('/', hostStart);
        std::string hp = (pathStart != std::string::npos) ? uri.substr(hostStart, pathStart - hostStart) : uri.substr(hostStart);
        if (!hp.empty() && hp.find("localhost") == std::string::npos && hp.find("127.0.0.1") == std::string::npos) {
            return hp;
        }
    }
    std::string h;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        h = mHost.empty() ? "localhost" : mHost;
    }
    return h + ":" + std::to_string(mPort);
}

// Deterministically derives an RFC-4122-shaped (version 5 style) UUID string from a name,
// so the same printer name always advertises the same printer-uuid across restarts, matching
// the UUID handed out via mDNS TXT records.
static std::string makeDeterministicUuid(const std::string &seed) {
    // FNV-1a 64-bit hash, expanded into 128 bits by hashing twice with different seeds.
    auto fnv1a = [](const std::string &s, uint64_t offset) -> uint64_t {
        uint64_t hash = offset;
        for (unsigned char c : s) {
            hash ^= c;
            hash *= 1099511628211ULL;
        }
        return hash;
    };
    uint64_t hi = fnv1a("cuppa-printer-uuid:" + seed, 14695981039346656037ULL);
    uint64_t lo = fnv1a(seed + ":cuppa-printer-uuid", 1469598103934665603ULL);

    // Force version 4 and RFC 4122 variant bits so the value is a well-formed UUID.
    hi = (hi & 0xFFFFFFFFFFFF0FFFULL) | 0x0000000000004000ULL;
    lo = (lo & 0x3FFFFFFFFFFFFFFFULL) | 0x8000000000000000ULL;

    char buf[37];
    snprintf(buf, sizeof(buf),
             "%08x-%04x-%04x-%04x-%012llx",
             static_cast<uint32_t>(hi >> 32),
             static_cast<uint32_t>((hi >> 16) & 0xFFFF),
             static_cast<uint32_t>(hi & 0xFFFF),
             static_cast<uint32_t>(lo >> 48),
             static_cast<unsigned long long>(lo & 0xFFFFFFFFFFFFULL));
    return std::string(buf);
}

std::shared_ptr<IppMessage> CupsServer::handleGetPrinterAttributes(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;

    std::string rawUri = req.getPrinterUri();
    std::string printerName = extractPrinterNameFromUri(rawUri);
    PrinterInfo printer;
    bool found = false;
    int32_t queued = 0;
    // A request aimed at a specific /printers/<name> queue must never be answered with some other
    // printer's attributes just because the name didn't match.
    bool namedQueue = rawUri.find("/printers/") != std::string::npos;

    {
        std::lock_guard<std::mutex> lock(mMutex);
        std::string resolved = resolvePrinterNameLocked(printerName);
        if (!resolved.empty()) {
            printer = mPrinters[resolved];
            found = true;
        }
        // If requested URI is generic (e.g. /ipp/print, /), fall back to default or any printer
        if (!found && !namedQueue && !mPrinters.empty()) {
            for (const auto &p : mPrinters) {
                if (p.second.isDefault) {
                    printer = p.second;
                    found = true;
                    break;
                }
            }
            if (!found) {
                printer = mPrinters.begin()->second;
                found = true;
            }
        }
        if (found) queued = queuedJobCountLocked(printer.name);
    }

    if (!found) {
        LOGW("handleGetPrinterAttributes: Printer not found for URI '%s' (extracted: '%s')",
             rawUri.c_str(), printerName.c_str());
        resp->status = IppStatus::CLIENT_NOT_FOUND;
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
        return resp;
    }

    LOGI("handleGetPrinterAttributes: Returning attributes for '%s' (%s)",
         printer.name.c_str(), printer.makeAndModel.c_str());

    resp->status = IppStatus::OK;
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");

    populatePrinterAttributes(*resp, printer, rawUri, queued);
    return resp;
}

namespace {

struct MediaDef {
    const char *name;
    int32_t x; // 1/100 mm
    int32_t y;
};

const MediaDef kMedia[] = {
    {"na_letter_8.5x11in", 21590, 27940},
    {"iso_a4_210x297mm", 21000, 29700},
    {"na_legal_8.5x14in", 21590, 35560},
    {"na_executive_7.25x10.5in", 18415, 26670},
    {"iso_a5_148x210mm", 14800, 21000},
    {"na_index-4x6_4x6in", 10160, 15240},
    {"oe_receipt_80x297mm", 8000, 29700},
};

const MediaDef *findMedia(const std::string &name) {
    for (const auto &m : kMedia) {
        if (name == m.name) return &m;
    }
    return nullptr;
}

const time_t kProcessStart = time(nullptr);

// Emits the members of one media-col collection (the caller opens/closes the collection itself).
void addMediaColMembers(IppMessage &r, IppTag g, const MediaDef &m, int32_t margin, const char *type) {
    r.addCollectionMemberName(g, "media-size");
    r.beginCollection(g, "");
    r.addCollectionMemberName(g, "x-dimension");
    r.addIntAttribute(g, IppTag::INTEGER, "", m.x);
    r.addCollectionMemberName(g, "y-dimension");
    r.addIntAttribute(g, IppTag::INTEGER, "", m.y);
    r.endCollection(g);
    for (const char *member : {"media-bottom-margin", "media-left-margin", "media-right-margin", "media-top-margin"}) {
        r.addCollectionMemberName(g, member);
        r.addIntAttribute(g, IppTag::INTEGER, "", margin);
    }
    r.addCollectionMemberName(g, "media-source");
    r.addAttribute(g, IppTag::KEYWORD, "", "auto");
    r.addCollectionMemberName(g, "media-type");
    r.addAttribute(g, IppTag::KEYWORD, "", type);
}

} // namespace

void CupsServer::populatePrinterAttributes(IppMessage &resp, const PrinterInfo &printer, const std::string &requestUri, int32_t queuedJobs) {
    IppTag grp = IppTag::PRINTER_ATTRIBUTES;

    // Host and port the client used to reach us. printer-uri-supported must point back at the
    // address the client is actually using, not whatever address the server was started on.
    std::string hostPort;
    size_t schemePos = requestUri.find("://");
    if (schemePos != std::string::npos) {
        size_t hostStart = schemePos + 3;
        size_t pathStart = requestUri.find('/', hostStart);
        std::string hp = (pathStart != std::string::npos) ? requestUri.substr(hostStart, pathStart - hostStart) : requestUri.substr(hostStart);
        if (!hp.empty() && hp.find("localhost") == std::string::npos && hp.find("127.0.0.1") == std::string::npos) {
            hostPort = hp;
        }
    }
    if (hostPort.empty()) {
        std::string h = mHost.empty() ? "localhost" : mHost;
        hostPort = h + ":" + std::to_string(mPort);
    }

    // Formats. image/urf is never advertised: URF needs a matching urf-supported capability string
    // (and the mDNS "URF" TXT key) that we can't truthfully supply for an arbitrary backing
    // printer, and clients that see image/urf without them abandon driverless setup.
    std::vector<std::string> formats;
    for (const auto &f : printer.supportedFormats) {
        if (f == "image/urf") continue;
        if (std::find(formats.begin(), formats.end(), f) == formats.end()) formats.push_back(f);
    }
    if (formats.empty()) {
        formats = {"application/pdf", "application/octet-stream"};
    }
    // Cuppa converts PDF to PWG-Raster itself for printers that only take raster (see
    // PrintJobDispatcher), so it can always accept PDF. Without this a Mac sees a printer that
    // takes "octet-stream" only and sends PostScript, which the real printer then rejects.
    if (std::find(formats.begin(), formats.end(), "image/pwg-raster") != formats.end() &&
        std::find(formats.begin(), formats.end(), "application/pdf") == formats.end()) {
        formats.insert(formats.begin(), "application/pdf");
    }
    bool hasPdf = std::find(formats.begin(), formats.end(), "application/pdf") != formats.end();
    bool hasPwg = std::find(formats.begin(), formats.end(), "image/pwg-raster") != formats.end();
    bool hasOctet = std::find(formats.begin(), formats.end(), "application/octet-stream") != formats.end();
    std::string defaultFormat = hasPdf ? "application/pdf" : (hasOctet ? "application/octet-stream" : formats.front());

    // Printer type (thermal/label vs standard office)
    bool isThermal = false;
    std::string lowerModel = printer.makeAndModel;
    std::transform(lowerModel.begin(), lowerModel.end(), lowerModel.begin(), ::tolower);
    std::string lowerName = printer.name;
    std::transform(lowerName.begin(), lowerName.end(), lowerName.begin(), ::tolower);

    if (lowerModel.find("rollo") != std::string::npos ||
        lowerModel.find("zebra") != std::string::npos ||
        lowerModel.find("thermal") != std::string::npos ||
        lowerModel.find("label") != std::string::npos ||
        lowerModel.find("dymo") != std::string::npos ||
        lowerModel.find("munbyn") != std::string::npos ||
        lowerModel.find("tsc") != std::string::npos ||
        lowerModel.find("pos") != std::string::npos ||
        lowerName.find("thermal") != std::string::npos ||
        lowerName.find("label") != std::string::npos ||
        lowerName.find("receipt") != std::string::npos ||
        lowerName.find("0x09c5") != std::string::npos) {
        isThermal = true;
    }
    int32_t resDpi = printer.resolutionDpi > 0 ? printer.resolutionDpi : (isThermal ? 203 : 300);

    std::string model = printer.makeAndModel.empty() ? "Generic IPP Printer" : printer.makeAndModel;
    std::string mfg = model.substr(0, model.find(' '));
    std::string mdl = model.find(' ') == std::string::npos ? model : model.substr(model.find(' ') + 1);
    std::string cmd;
    if (hasPdf) cmd += "PDF,";
    if (hasPwg) cmd += "PWGRaster,";
    if (!cmd.empty()) cmd.pop_back();

    resp.addAttribute(grp, IppTag::NAME_WITHOUT_LANGUAGE, "printer-name", printer.name);
    // With "Require IPPS" on, plain IPP is refused. Say so, or a client that reads this will try
    // ipp:// and fail, which is what Linux setup dialogs did.
    // A printer whose own address is ipps:// is also described as secure-only, even with the global
    // setting off. Clients that see ipp:// for it just offer a driver choice that goes nowhere.
    auto startsWith = [](const std::string &s, const char *prefix) { return strncasecmp(s.c_str(), prefix, strlen(prefix)) == 0; };
    const bool tlsOnly = mTlsRequired.load() || startsWith(printer.uri, "ipps://") || startsWith(printer.uri, "https://");
    resp.addAttribute(grp, IppTag::URI, "printer-uri-supported",
                      std::string(tlsOnly ? "ipps://" : "ipp://") + hostPort + "/printers/" + sanitizeResourceName(printer.name));
    resp.addAttribute(grp, IppTag::KEYWORD, "uri-security-supported", tlsOnly ? "tls" : "none");
    resp.addAttribute(grp, IppTag::KEYWORD, "uri-authentication-supported", "none");
    resp.addAttribute(grp, IppTag::URI, "printer-uuid", "urn:uuid:" + makeDeterministicUuid(printer.name));
    resp.addAttribute(grp, IppTag::TEXT_WITHOUT_LANGUAGE, "printer-info", printer.info.empty() ? printer.name : printer.info);
    resp.addAttribute(grp, IppTag::TEXT_WITHOUT_LANGUAGE, "printer-location", printer.location.empty() ? "Android CUPS Server" : printer.location);
    resp.addAttribute(grp, IppTag::TEXT_WITHOUT_LANGUAGE, "printer-make-and-model", model);
    resp.addAttribute(grp, IppTag::URI, "printer-more-info", "http://" + hostPort + "/");
    resp.addAttribute(grp, IppTag::TEXT_WITHOUT_LANGUAGE, "printer-device-id",
                      "MFG:" + mfg + ";MDL:" + mdl + ";CMD:" + cmd + ";CLS:PRINTER;");

    // Printer state: 3 = idle, 4 = processing, 5 = stopped
    resp.addIntAttribute(grp, IppTag::ENUM, "printer-state", printer.state);
    resp.addAttribute(grp, IppTag::KEYWORD, "printer-state-reasons", "none");
    resp.addBoolAttribute(grp, "printer-is-accepting-jobs", printer.isAcceptingJobs);
    resp.addIntAttribute(grp, IppTag::INTEGER, "queued-job-count", queuedJobs);
    resp.addIntAttribute(grp, IppTag::INTEGER, "printer-up-time", static_cast<int32_t>(time(nullptr) - kProcessStart) + 1);
    resp.addDateTimeAttribute(grp, "printer-current-time", static_cast<int64_t>(time(nullptr)));

    // IPP Versions supported
    resp.addAttribute(grp, IppTag::KEYWORD, "ipp-versions-supported", "1.1");
    resp.addAttribute(grp, IppTag::KEYWORD, "ipp-versions-supported", "2.0");

    // Operations supported. macOS/CUPS-derived clients and the Windows IPP class driver
    // both prefer Create-Job + Send-Document over a single Print-Job for driverless queues,
    // so all three job-submission flows must be advertised and implemented (see handleCreateJob,
    // handleSendDocument, handlePrintJob in cups_server.cpp).
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::PRINT_JOB));
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::VALIDATE_JOB));
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::CREATE_JOB));
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::SEND_DOCUMENT));
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::GET_JOB_ATTRIBUTES));
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::GET_JOBS));
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::CANCEL_JOB));
    resp.addIntAttribute(grp, IppTag::ENUM, "operations-supported", static_cast<int32_t>(IppOp::GET_PRINTER_ATTRIBUTES));

    resp.addAttribute(grp, IppTag::KEYWORD, "multiple-operation-time-out-action", "abort-job");
    resp.addIntAttribute(grp, IppTag::INTEGER, "multiple-operation-time-out", 120);
    resp.addAttribute(grp, IppTag::KEYWORD, "compression-supported", "none");

    // Formats supported
    // A printer that takes PWG-Raster is a page printer, whatever its name looks like, and clients
    // render the raster at whatever resolution is advertised here. Advertising 203 dpi for a
    // laser printer made the Mac send 203 dpi raster that the Brother refused (document-format-error).
    if (hasPwg && printer.resolutionDpi <= 0) {
        resDpi = 300;
        isThermal = false;
    }
    for (const auto &fmt : formats) {
        resp.addAttribute(grp, IppTag::MIME_MEDIA_TYPE, "document-format-supported", fmt);
    }
    resp.addAttribute(grp, IppTag::MIME_MEDIA_TYPE, "document-format-default", defaultFormat);
    if (hasPdf) {
        for (const char *v : {"adobe-1.4", "adobe-1.5", "adobe-1.6", "iso-32000-1_2008"}) {
            resp.addAttribute(grp, IppTag::KEYWORD, "pdf-versions-supported", v);
        }
    }
    if (hasPwg) {
        resp.addResolutionAttribute(grp, "pwg-raster-document-resolution-supported", resDpi, resDpi, 3);
        resp.addAttribute(grp, IppTag::KEYWORD, "pwg-raster-document-sheet-back", "normal");
        resp.addAttribute(grp, IppTag::KEYWORD, "pwg-raster-document-type-supported", "sgray_8");
        if (printer.colorSupported) {
            resp.addAttribute(grp, IppTag::KEYWORD, "pwg-raster-document-type-supported", "srgb_8");
        }
    }

    // Color & Resolution
    resp.addBoolAttribute(grp, "color-supported", printer.colorSupported);
    resp.addResolutionAttribute(grp, "printer-resolution-supported", resDpi, resDpi, 3); // 3 = dpi
    resp.addResolutionAttribute(grp, "printer-resolution-default", resDpi, resDpi, 3);
    resp.addAttribute(grp, IppTag::KEYWORD, "print-color-mode-supported", "monochrome");
    if (printer.colorSupported) {
        resp.addAttribute(grp, IppTag::KEYWORD, "print-color-mode-supported", "color");
        resp.addAttribute(grp, IppTag::KEYWORD, "print-color-mode-supported", "auto");
    }
    resp.addAttribute(grp, IppTag::KEYWORD, "print-color-mode-default", printer.colorSupported ? "auto" : "monochrome");
    for (int32_t q : {3, 4, 5}) resp.addIntAttribute(grp, IppTag::ENUM, "print-quality-supported", q);
    resp.addIntAttribute(grp, IppTag::ENUM, "print-quality-default", 4);
    for (int32_t o : {3, 4, 5, 6}) resp.addIntAttribute(grp, IppTag::ENUM, "orientation-requested-supported", o);
    resp.addIntAttribute(grp, IppTag::ENUM, "orientation-requested-default", 3);
    resp.addIntAttribute(grp, IppTag::ENUM, "finishings-supported", 3);
    resp.addIntAttribute(grp, IppTag::ENUM, "finishings-default", 3);
    resp.addIntAttribute(grp, IppTag::INTEGER, "number-up-supported", 1);
    resp.addIntAttribute(grp, IppTag::INTEGER, "number-up-default", 1);
    resp.addBoolAttribute(grp, "page-ranges-supported", false);

    // Media
    std::vector<std::string> mediaList;
    std::string mediaDefault;
    if (isThermal) {
        mediaList = {"na_index-4x6_4x6in", "oe_receipt_80x297mm", "na_letter_8.5x11in", "iso_a4_210x297mm"};
        mediaDefault = "na_index-4x6_4x6in";
    } else {
        mediaList = {"na_letter_8.5x11in", "iso_a4_210x297mm", "na_legal_8.5x14in", "na_executive_7.25x10.5in", "iso_a5_148x210mm", "na_index-4x6_4x6in"};
        mediaDefault = "na_letter_8.5x11in";
    }
    for (const auto &m : mediaList) resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", m);
    resp.addAttribute(grp, IppTag::KEYWORD, "media-default", mediaDefault);
    resp.addAttribute(grp, IppTag::KEYWORD, "media-ready", mediaDefault);

    int32_t margin = isThermal ? 0 : 423;
    const char *mediaType = isThermal ? "labels" : "stationery";
    const MediaDef *defMedia = findMedia(mediaDefault);
    if (defMedia) {
        resp.beginCollection(grp, "media-col-default");
        addMediaColMembers(resp, grp, *defMedia, margin, mediaType);
        resp.endCollection(grp);
        resp.beginCollection(grp, "media-col-ready");
        addMediaColMembers(resp, grp, *defMedia, margin, mediaType);
        resp.endCollection(grp);
    }
    bool firstDb = true;
    for (const auto &m : mediaList) {
        const MediaDef *md = findMedia(m);
        if (!md) continue;
        resp.beginCollection(grp, firstDb ? "media-col-database" : "");
        addMediaColMembers(resp, grp, *md, margin, mediaType);
        resp.endCollection(grp);
        firstDb = false;
    }
    for (const char *member : {"media-bottom-margin", "media-left-margin", "media-right-margin", "media-size", "media-source", "media-top-margin", "media-type"}) {
        resp.addAttribute(grp, IppTag::KEYWORD, "media-col-supported", member);
    }
    resp.addAttribute(grp, IppTag::KEYWORD, "media-source-supported", "auto");
    resp.addAttribute(grp, IppTag::KEYWORD, "media-type-supported", mediaType);
    for (const char *name : {"media-bottom-margin-supported", "media-left-margin-supported", "media-right-margin-supported", "media-top-margin-supported"}) {
        resp.addIntAttribute(grp, IppTag::INTEGER, name, margin);
    }

    // Sides & Copies
    resp.addAttribute(grp, IppTag::KEYWORD, "sides-supported", "one-sided");
    resp.addAttribute(grp, IppTag::KEYWORD, "sides-default", "one-sided");
    resp.addRangeAttribute(grp, "copies-supported", 1, 99);
    resp.addIntAttribute(grp, IppTag::INTEGER, "copies-default", 1);
    for (const char *k : {"copies", "media", "media-col", "orientation-requested", "print-color-mode", "print-quality", "printer-resolution", "sides"}) {
        resp.addAttribute(grp, IppTag::KEYWORD, "job-creation-attributes-supported", k);
    }

    // PDL Override
    resp.addAttribute(grp, IppTag::KEYWORD, "pdl-override-supported", "not-attempted");
    resp.addAttribute(grp, IppTag::CHARSET, "charset-configured", "utf-8");
    resp.addAttribute(grp, IppTag::CHARSET, "charset-supported", "utf-8");
    resp.addAttribute(grp, IppTag::NATURAL_LANGUAGE, "natural-language-configured", "en-us");
    resp.addAttribute(grp, IppTag::NATURAL_LANGUAGE, "generated-natural-language-supported", "en-us");
}

std::shared_ptr<IppMessage> CupsServer::handleValidateJob(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;
    resp->status = IppStatus::OK;

    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    return resp;
}

static const char *jobStateReason(int32_t state) {
    switch (state) {
        case 3: return "none";
        case 4: return "job-incoming";
        case 5: return "job-printing";
        case 6: return "printer-stopped";
        case 7: return "job-canceled-by-user";
        case 8: return "aborted-by-system";
        case 9: return "job-completed-successfully";
        default: return "none";
    }
}

std::string CupsServer::spoolPathForJob(int32_t jobId) const {
    return mSpoolDir + "/job_" + std::to_string(jobId) + ".prn";
}

void CupsServer::populateJobAttributes(IppMessage &resp, const PrintJob &job, const std::string &hostPortIn) const {
    IppTag jgrp = IppTag::JOB_ATTRIBUTES;
    // Use the address the client reached us on. The configured mHost is only set when the server
    // starts, so after the phone changes networks it goes stale and would leak the old IP here.
    std::string hostPort = hostPortIn;
    if (hostPort.empty()) {
        hostPort = (mHost.empty() ? "localhost" : mHost) + ":" + std::to_string(mPort);
    }
    resp.addIntAttribute(jgrp, IppTag::INTEGER, "job-id", job.jobId);
    resp.addAttribute(jgrp, IppTag::URI, "job-uri", "ipp://" + hostPort + "/jobs/" + std::to_string(job.jobId));
    resp.addAttribute(jgrp, IppTag::URI, "job-printer-uri", "ipp://" + hostPort + "/printers/" + sanitizeResourceName(job.printerName));
    resp.addAttribute(jgrp, IppTag::NAME_WITHOUT_LANGUAGE, "job-name", job.jobName);
    resp.addAttribute(jgrp, IppTag::NAME_WITHOUT_LANGUAGE, "job-originating-user-name", job.user);
    resp.addIntAttribute(jgrp, IppTag::ENUM, "job-state", job.state);
    resp.addAttribute(jgrp, IppTag::KEYWORD, "job-state-reasons", jobStateReason(job.state));
    resp.addIntAttribute(jgrp, IppTag::INTEGER, "job-k-octets", static_cast<int32_t>((job.dataSize + 1023) / 1024));
}

// Print-Job: single-shot submission carrying the full document in one request. This is the
// path most AirPrint clients (and Cuppa's own USB pipeline) use for small jobs.
std::shared_ptr<IppMessage> CupsServer::handlePrintJob(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;

    std::string printerName = extractPrinterNameFromUri(req.getPrinterUri());
    {
        std::lock_guard<std::mutex> lock(mMutex);
        std::string resolved = resolvePrinterNameLocked(printerName);
        if (!resolved.empty()) {
            printerName = resolved;
        } else if (req.getPrinterUri().find("/printers/") == std::string::npos) {
            printerName = getDefaultPrinterNameLocked(); // Can return "" if no printers exist
        } else {
            printerName.clear(); // a specific queue that doesn't exist: don't misroute to the default
        }
    }

    if (printerName.empty()) {
        resp->status = IppStatus::CLIENT_NOT_FOUND;
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
        LOGI("Print job rejected: No printers available or printer not found");
        return resp;
    }

    PrintJob job;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        job.jobId = mNextJobId++;
        job.printerName = printerName;
        job.jobName = req.getJobName();
        job.user = req.getRequestingUserName();
        job.dataSize = req.documentData.size();
        if (const auto *c = req.findAttribute("copies")) {
            job.copies = std::max<int32_t>(1, std::min<int32_t>(999, c->asInt()));
        }
        job.options = collectJobOptions(req);
        job.state = 3; // Pending — PrintJobDispatcher (Kotlin) picks this up asynchronously
        job.createdAt = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();

        // Write spooled document file if spool directory is configured
        if (!mSpoolDir.empty() && !req.documentData.empty()) {
            std::string spoolPath = spoolPathForJob(job.jobId);
            std::ofstream out(spoolPath, std::ios::binary);
            if (out.is_open()) {
                out.write(reinterpret_cast<const char*>(req.documentData.data()), req.documentData.size());
                out.close();
                job.spoolFilePath = spoolPath;
                const auto *fmt = req.findAttribute("document-format");
                LOGI("Spooled job #%d (%zu bytes, %d cop%s, client format=%s) to %s", job.jobId, req.documentData.size(),
                     job.copies, job.copies == 1 ? "y" : "ies", fmt ? fmt->asString().c_str() : "(none)", spoolPath.c_str());
            } else {
                // Accepting a job we cannot store just makes it vanish. Say so to the client.
                LOGE("Print-Job rejected: could not open spool file %s (errno=%d)", spoolPath.c_str(), errno);
                mNextJobId--;
                resp->status = IppStatus::SERVER_INTERNAL_ERROR;
                resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
                resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
                return resp;
            }
        }

        mJobs.push_back(job);
    }

    resp->status = IppStatus::OK;
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    populateJobAttributes(*resp, job, hostPortForRequest(req));

    LOGI("Successfully created job #%d on printer '%s'", job.jobId, printerName.c_str());
    return resp;
}

// Create-Job: opens a job with no document data yet. macOS's CUPS-derived driverless backend
// and the Windows inbox IPP class driver both default to this Create-Job + Send-Document flow
// rather than a single Print-Job, so without this the printer appears but every print silently
// fails with "operation not supported".
std::shared_ptr<IppMessage> CupsServer::handleCreateJob(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;

    std::string printerName = extractPrinterNameFromUri(req.getPrinterUri());
    {
        std::lock_guard<std::mutex> lock(mMutex);
        std::string resolved = resolvePrinterNameLocked(printerName);
        if (!resolved.empty()) {
            printerName = resolved;
        } else if (req.getPrinterUri().find("/printers/") == std::string::npos) {
            printerName = getDefaultPrinterNameLocked();
        } else {
            printerName.clear();
        }
    }

    if (printerName.empty()) {
        resp->status = IppStatus::CLIENT_NOT_FOUND;
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
        LOGI("Create-Job rejected: No printers available or printer not found");
        return resp;
    }

    PrintJob job;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        job.jobId = mNextJobId++;
        job.printerName = printerName;
        job.jobName = req.getJobName();
        job.user = req.getRequestingUserName();
        if (const auto *c = req.findAttribute("copies")) {
            job.copies = std::max<int32_t>(1, std::min<int32_t>(999, c->asInt()));
        }
        job.options = collectJobOptions(req);
        job.state = 4; // Held — awaiting a Send-Document call
        job.awaitingDocument = true;
        job.createdAt = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        mJobs.push_back(job);
    }

    resp->status = IppStatus::OK;
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    populateJobAttributes(*resp, job, hostPortForRequest(req));

    LOGI("Create-Job opened job #%d on printer '%s', awaiting document", job.jobId, printerName.c_str());
    return resp;
}

// Send-Document: appends document bytes to a job opened by Create-Job. When the client sets
// last-document=true (the default if omitted, since nearly all real-world clients send exactly
// one Send-Document per job) the job transitions to Pending so PrintJobDispatcher can pick it up.
std::shared_ptr<IppMessage> CupsServer::handleSendDocument(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;

    const auto *jobIdAttr = req.findAttribute("job-id");
    int32_t jobId = jobIdAttr ? jobIdAttr->asInt() : 0;

    const auto *lastDocAttr = req.findAttribute("last-document");
    bool lastDocument = lastDocAttr ? lastDocAttr->asBool() : true;

    PrintJob jobCopy;
    bool found = false;
    bool wroteDoc = false;

    {
        std::lock_guard<std::mutex> lock(mMutex);
        for (auto &job : mJobs) {
            if (job.jobId != jobId) continue;
            found = true;

            if (!req.documentData.empty()) {
                std::string spoolPath = job.spoolFilePath.empty() ? spoolPathForJob(job.jobId) : job.spoolFilePath;
                std::ofstream out(spoolPath, std::ios::binary | std::ios::app);
                if (out.is_open()) {
                    out.write(reinterpret_cast<const char*>(req.documentData.data()), req.documentData.size());
                    out.close();
                    job.spoolFilePath = spoolPath;
                    job.dataSize += req.documentData.size();
                    wroteDoc = true;
                }
            }

            if (lastDocument) {
                job.awaitingDocument = false;
                job.state = 3; // Pending — ready for PrintJobDispatcher to send to the printer
            }

            jobCopy = job;
            break;
        }
    }

    if (!found) {
        resp->status = IppStatus::CLIENT_NOT_FOUND;
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
        resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
        LOGW("Send-Document: job #%d not found", jobId);
        return resp;
    }

    resp->status = IppStatus::OK;
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    populateJobAttributes(*resp, jobCopy, hostPortForRequest(req));

    {
        const auto *fmt = req.findAttribute("document-format");
        LOGI("Send-Document: job #%d received %zu bytes (last=%d, wrote=%d), state=%d, client format=%s",
             jobId, req.documentData.size(), lastDocument, wroteDoc, jobCopy.state,
             fmt ? fmt->asString().c_str() : "(none)");
    }
    return resp;
}

std::shared_ptr<IppMessage> CupsServer::handleGetJobAttributes(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;

    const auto *jobIdAttr = req.findAttribute("job-id");
    int32_t jobId = jobIdAttr ? jobIdAttr->asInt() : 0;

    PrintJob jobCopy;
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        for (const auto &job : mJobs) {
            if (job.jobId == jobId) {
                jobCopy = job;
                found = true;
                break;
            }
        }
    }

    resp->status = found ? IppStatus::OK : IppStatus::CLIENT_NOT_FOUND;
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    if (found) {
        populateJobAttributes(*resp, jobCopy, hostPortForRequest(req));
    }
    return resp;
}

std::shared_ptr<IppMessage> CupsServer::handleGetJobs(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;
    resp->status = IppStatus::OK;

    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");

    std::string hostPort = hostPortForRequest(req); // takes mMutex itself, so call before locking
    std::string wantedName;
    {
        std::lock_guard<std::mutex> lock(mMutex);
        wantedName = resolvePrinterNameLocked(extractPrinterNameFromUri(req.getPrinterUri()));
    }
    const auto *whichJobs = req.findAttribute("which-jobs");
    std::string which = whichJobs ? whichJobs->asString() : "not-completed";
    const auto *myJobs = req.findAttribute("my-jobs");
    bool onlyMine = myJobs && myJobs->asBool();
    std::string me = req.getRequestingUserName();

    std::lock_guard<std::mutex> lock(mMutex);
    for (const auto &job : mJobs) {
        if (!wantedName.empty() && job.printerName != wantedName) continue;
        bool completed = (job.state >= 7);
        if (which == "completed" ? !completed : (which == "not-completed" && completed)) continue;
        if (onlyMine && job.user != me) continue;
        populateJobAttributes(*resp, job, hostPort);
    }

    return resp;
}

std::shared_ptr<IppMessage> CupsServer::handleCancelJob(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;

    const auto *attr = req.findAttribute("job-id");
    int32_t jobId = attr ? attr->asInt() : 0;

    bool ok = cancelJob(jobId);
    resp->status = ok ? IppStatus::OK : IppStatus::CLIENT_NOT_FOUND;

    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    return resp;
}

std::shared_ptr<IppMessage> CupsServer::handleCupsGetPrinters(const IppMessage &req) {
    auto resp = std::make_shared<IppMessage>();
    resp->version = req.version;
    resp->requestId = req.requestId;
    resp->status = IppStatus::OK;

    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");

    std::lock_guard<std::mutex> lock(mMutex);
    for (const auto &pair : mPrinters) {
        populatePrinterAttributes(*resp, pair.second, "", queuedJobCountLocked(pair.second.name));
    }

    return resp;
}

} // namespace cuppa
