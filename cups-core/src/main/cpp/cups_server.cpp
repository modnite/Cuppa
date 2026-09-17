#include "cups_server.h"
#include <fstream>
#include <chrono>
#include <sstream>
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

    {
        std::lock_guard<std::mutex> lock(mMutex);
        // 1. Direct name lookup
        if (!printerName.empty() && mPrinters.find(printerName) != mPrinters.end()) {
            printer = mPrinters[printerName];
            found = true;
        }
        // 2. Lookup by URI or case-insensitive search
        if (!found) {
            for (const auto &p : mPrinters) {
                if (p.second.uri == rawUri || p.second.name == printerName ||
                    strcasecmp(p.second.name.c_str(), printerName.c_str()) == 0) {
                    printer = p.second;
                    found = true;
                    break;
                }
            }
        }
        // 3. If requested URI is generic (e.g. /ipp/print, /, /printers), fallback to default or any printer
        if (!found && !mPrinters.empty()) {
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

    populatePrinterAttributes(*resp, printer, rawUri);
    return resp;
}

void CupsServer::populatePrinterAttributes(IppMessage &resp, const PrinterInfo &printer, const std::string &requestUri) {
    IppTag grp = IppTag::PRINTER_ATTRIBUTES;

    // Dynamically determine host and port for printer-uri-supported
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

    resp.addAttribute(grp, IppTag::NAME_WITHOUT_LANGUAGE, "printer-name", printer.name);
    resp.addAttribute(grp, IppTag::URI, "printer-uri-supported", "ipp://" + hostPort + "/printers/" + printer.name);
    resp.addAttribute(grp, IppTag::URI, "printer-uuid", "urn:uuid:" + makeDeterministicUuid(printer.name));
    resp.addAttribute(grp, IppTag::TEXT_WITHOUT_LANGUAGE, "printer-info", printer.info.empty() ? printer.name : printer.info);
    resp.addAttribute(grp, IppTag::TEXT_WITHOUT_LANGUAGE, "printer-location", printer.location.empty() ? "Android CUPS Server" : printer.location);
    resp.addAttribute(grp, IppTag::TEXT_WITHOUT_LANGUAGE, "printer-make-and-model", printer.makeAndModel.empty() ? "Generic IPP Printer" : printer.makeAndModel);

    // Printer state: 3 = idle, 4 = processing, 5 = stopped
    resp.addIntAttribute(grp, IppTag::ENUM, "printer-state", printer.state);
    resp.addAttribute(grp, IppTag::KEYWORD, "printer-state-reasons", "none");
    resp.addBoolAttribute(grp, "printer-is-accepting-jobs", printer.isAcceptingJobs);

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

    // Multiple-operation-time and job-creation attributes that IPP Everywhere / AirPrint
    // validators check for before treating a queue as "driverless".
    resp.addAttribute(grp, IppTag::KEYWORD, "multiple-operation-time-out-action", "abort-job");
    resp.addIntAttribute(grp, IppTag::INTEGER, "multiple-operation-time-out", 120);
    resp.addAttribute(grp, IppTag::KEYWORD, "compression-supported", "none");

    // Formats supported
    for (const auto &fmt : printer.supportedFormats) {
        resp.addAttribute(grp, IppTag::MIME_MEDIA_TYPE, "document-format-supported", fmt);
    }
    resp.addAttribute(grp, IppTag::MIME_MEDIA_TYPE, "document-format-default", "application/pdf");

    // Determine printer type (thermal/label vs standard office)
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
        lowerName.find("receipt") != std::string::npos) {
        isThermal = true;
    }

    int32_t resDpi = printer.resolutionDpi > 0 ? printer.resolutionDpi : (isThermal ? 203 : 300);

    // Color & Resolution
    resp.addBoolAttribute(grp, "color-supported", printer.colorSupported);
    resp.addResolutionAttribute(grp, "printer-resolution-supported", resDpi, resDpi, 3); // 3 = dpi
    resp.addResolutionAttribute(grp, "printer-resolution-default", resDpi, resDpi, 3);

    // Media
    if (isThermal) {
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "na_index-4x6_4x6in");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "oe_receipt_80x297mm");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "na_letter_8.5x11in");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "iso_a4_210x297mm");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-default", "na_index-4x6_4x6in");
    } else {
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "na_letter_8.5x11in");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "iso_a4_210x297mm");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "na_legal_8.5x14in");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "na_executive_7.25x10.5in");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "iso_a5_148x210mm");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-supported", "na_index-4x6_4x6in");
        resp.addAttribute(grp, IppTag::KEYWORD, "media-default", "na_letter_8.5x11in");
    }

    // Sides & Copies
    resp.addAttribute(grp, IppTag::KEYWORD, "sides-supported", "one-sided");
    resp.addAttribute(grp, IppTag::KEYWORD, "sides-default", "one-sided");
    resp.addIntAttribute(grp, IppTag::INTEGER, "copies-default", 1);

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

void CupsServer::populateJobAttributes(IppMessage &resp, const PrintJob &job) const {
    IppTag jgrp = IppTag::JOB_ATTRIBUTES;
    std::string jobHost = mHost.empty() ? "localhost" : mHost;
    resp.addIntAttribute(jgrp, IppTag::INTEGER, "job-id", job.jobId);
    resp.addAttribute(jgrp, IppTag::URI, "job-uri", "ipp://" + jobHost + ":" + std::to_string(mPort) + "/jobs/" + std::to_string(job.jobId));
    resp.addAttribute(jgrp, IppTag::URI, "job-printer-uri", "ipp://" + jobHost + ":" + std::to_string(mPort) + "/printers/" + job.printerName);
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
        if (printerName.empty() || mPrinters.find(printerName) == mPrinters.end()) {
            printerName = getDefaultPrinterNameLocked(); // Can return "" if no printers exist
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
                LOGI("Spooled job #%d (%zu bytes) to %s", job.jobId, req.documentData.size(), spoolPath.c_str());
            }
        }

        mJobs.push_back(job);
    }

    resp->status = IppStatus::OK;
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    populateJobAttributes(*resp, job);

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
        if (printerName.empty() || mPrinters.find(printerName) == mPrinters.end()) {
            printerName = getDefaultPrinterNameLocked();
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
        job.state = 4; // Held — awaiting a Send-Document call
        job.awaitingDocument = true;
        job.createdAt = std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
        mJobs.push_back(job);
    }

    resp->status = IppStatus::OK;
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::CHARSET, "attributes-charset", "utf-8");
    resp->addAttribute(IppTag::OPERATION_ATTRIBUTES, IppTag::NATURAL_LANGUAGE, "attributes-natural-language", "en-us");
    populateJobAttributes(*resp, job);

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
    populateJobAttributes(*resp, jobCopy);

    LOGI("Send-Document: job #%d received %zu bytes (last=%d, wrote=%d), state=%d",
         jobId, req.documentData.size(), lastDocument, wroteDoc, jobCopy.state);
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
        populateJobAttributes(*resp, jobCopy);
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

    std::lock_guard<std::mutex> lock(mMutex);
    for (const auto &job : mJobs) {
        populateJobAttributes(*resp, job);
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
        populatePrinterAttributes(*resp, pair.second);
    }

    return resp;
}

} // namespace cuppa
