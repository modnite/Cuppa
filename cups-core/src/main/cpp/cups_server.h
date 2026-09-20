#ifndef CUPS_SERVER_H
#define CUPS_SERVER_H

#include "cups_ipp.h"
#include <string>
#include <vector>
#include <map>
#include <mutex>
#include <atomic>

namespace cuppa {

struct PrinterInfo {
    std::string name;
    std::string uri;
    std::string makeAndModel;
    std::string ppdPath;
    std::string location;
    std::string info;
    bool isDefault = false;
    bool isAcceptingJobs = true;
    int32_t state = 3; // 3 = idle, 4 = processing, 5 = stopped
    // Only formats the pipeline can actually turn into printer output belong here:
    // application/pdf is rasterized locally via Android's PdfRenderer (see PrintJobDispatcher.kt),
    // and application/octet-stream / text/plain are streamed through verbatim as an already-native
    // printer language (ESC/POS, ZPL, TSPL, EPL). Do NOT advertise image/pwg-raster or image/urf —
    // there is no PWG-Raster/URF decoder in this build, so claiming them causes AirPrint/IPP-Everywhere
    // clients (which prefer raster formats for driverless queues) to send a format the printer pipeline
    // cannot decode, silently producing no output.
    std::vector<std::string> supportedFormats = {
        "application/pdf",
        "application/octet-stream",
        "text/plain"
    };
    bool colorSupported = false;
    int32_t resolutionDpi = 0; // 0 = unknown; the attribute code picks 203 for thermal, 300 otherwise
};

struct PrintJob {
    int32_t jobId = 1;
    std::string jobName;
    std::string printerName;
    std::string user;
    std::string documentFormat;
    int32_t state = 9; // 9 = completed, 7 = canceled, 5 = processing, 3 = pending
    size_t dataSize = 0;
    std::string spoolFilePath;
    int64_t createdAt = 0;
    bool awaitingDocument = false; // true between Create-Job and a final Send-Document
    int32_t copies = 1;            // the job's "copies" attribute, honored by the dispatcher
    std::string options;           // forwardable job-template attributes, one "name=value" per line
};

class CupsServer {
public:
    static CupsServer& getInstance();

    bool start(int port, const std::string &spoolDir, const std::string &configDir);
    void stop();
    bool isRunning() const;

    int getPort() const { return mPort; }
    std::string getSpoolDir() const { return mSpoolDir; }
    std::string getConfigDir() const { return mConfigDir; }

    /** True when plain IPP is refused, so the queue must be described and advertised as ipps. */
    void setTlsRequired(bool required) { mTlsRequired = required; }

    void setHost(const std::string &host) {
        std::lock_guard<std::mutex> lock(mMutex);
        mHost = host;
    }
    std::string getHost() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mHost;
    }

    // Printer Registry
    bool addPrinter(const PrinterInfo &info);
    bool removePrinter(const std::string &name);
    void clearPrinters();
    std::vector<PrinterInfo> getPrinters() const;
    bool getPrinter(const std::string &name, PrinterInfo &out) const;
    std::string getDefaultPrinterName() const;
    // Assumes the caller already holds mMutex. std::mutex is non-recursive, so calling
    // getDefaultPrinterName() (which locks mMutex itself) from inside a block that already
    // holds the lock deadlocks the calling thread permanently — and since mMutex is never
    // released, every subsequent request needing it hangs too. Use this instead in that case.
    std::string getDefaultPrinterNameLocked() const;

    // Jobs
    std::vector<PrintJob> getJobs(const std::string &printerName = "") const;
    bool cancelJob(int32_t jobId);
    bool updateJobState(int32_t jobId, int32_t state);

    // IPP Engine Entry Point
    std::vector<uint8_t> processIppRequest(const uint8_t *data, size_t size);

private:
    CupsServer();
    ~CupsServer() = default;

    // Operation handlers
    std::shared_ptr<IppMessage> handleGetPrinterAttributes(const IppMessage &req);
    std::shared_ptr<IppMessage> handleValidateJob(const IppMessage &req);
    std::shared_ptr<IppMessage> handlePrintJob(const IppMessage &req);
    std::shared_ptr<IppMessage> handleCreateJob(const IppMessage &req);
    std::shared_ptr<IppMessage> handleSendDocument(const IppMessage &req);
    std::shared_ptr<IppMessage> handleGetJobAttributes(const IppMessage &req);
    std::shared_ptr<IppMessage> handleGetJobs(const IppMessage &req);
    std::shared_ptr<IppMessage> handleCancelJob(const IppMessage &req);
    std::shared_ptr<IppMessage> handleCupsGetPrinters(const IppMessage &req);

    void populatePrinterAttributes(IppMessage &resp, const PrinterInfo &printer, const std::string &requestUri = "", int32_t queuedJobs = 0);
    void populateJobAttributes(IppMessage &resp, const PrintJob &job, const std::string &hostPort = "") const;
    // Host:port the client used to reach us (from its printer-uri/job-uri), falling back to the
    // address the server itself was configured with.
    std::string hostPortForRequest(const IppMessage &req) const;
    // Maps the last path segment of a printer URI back to a registered printer name. Registered
    // names contain spaces and punctuation but URIs can't, so both sides are compared in their
    // sanitized resource form. Assumes the caller holds mMutex. Returns "" if nothing matches.
    std::string resolvePrinterNameLocked(const std::string &uriSegment) const;
    int32_t queuedJobCountLocked(const std::string &printerName) const;
    std::string spoolPathForJob(int32_t jobId) const;

    mutable std::mutex mMutex;
    std::atomic<bool> mRunning{false};
    int mPort = 631;
    std::string mHost = "127.0.0.1";
    std::atomic<bool> mTlsRequired{false};
    std::string mSpoolDir;
    std::string mConfigDir;

    std::map<std::string, PrinterInfo> mPrinters;
    std::vector<PrintJob> mJobs;
    int32_t mNextJobId = 1;
};

} // namespace cuppa

#endif // CUPS_SERVER_H
