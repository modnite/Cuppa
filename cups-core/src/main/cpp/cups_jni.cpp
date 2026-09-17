/**
 * cups_jni.cpp - JNI bridge for Cuppa native layer
 *
 * Phase 1: Real CUPS client library integration (CUPS 2.2.9 source from AOSP).
 * Provides JNI bindings for:
 * - Version & server lifecycle control
 * - Querying IPP printer attributes via httpConnect2 & cupsDoRequest
 * - Submitting print jobs via cupsDoFileRequest
 * - Querying IPP print jobs
 */

#include <jni.h>
#include <string>
#include <vector>
#include <cstring>
#include <fstream>
#include <android/log.h>

#include "cups/cups.h"
#include "cups/http.h"
#include "cups/ipp.h"
#include "cups/config.h"
#include "cups/raster.h"
#include "cups/pwg.h"
#include "cups_server.h"

#include <fcntl.h>
#include <unistd.h>

#define LOG_TAG "CuppaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

/**
 * Returns the native library version string.
 */
JNIEXPORT jstring JNICALL
Java_com_cuppa_cups_CupsEngine_nativeGetVersion(
    JNIEnv *env,
    jobject /* this */
) {
    std::string version = std::string(CUPS_SVERSION) + " (libcups client)";
    LOGI("nativeGetVersion: %s", version.c_str());
    return env->NewStringUTF(version.c_str());
}

/**
 * Start the CUPS subsystem / embedded responder.
 */
JNIEXPORT jboolean JNICALL
Java_com_cuppa_cups_CupsEngine_nativeStartServer(
    JNIEnv *env,
    jobject /* this */,
    jint port,
    jstring configPath
) {
    const char *path = env->GetStringUTFChars(configPath, nullptr);
    std::string cfgDir = path ? path : "";
    if (path) env->ReleaseStringUTFChars(configPath, path);

    std::string spoolDir = cfgDir + "/spool";

    cupsSetServer("localhost");
    ippSetPort(port);

    bool ok = cuppa::CupsServer::getInstance().start(port, spoolDir, cfgDir);
    LOGI("nativeStartServer: port=%d, configPath=%s, started=%d", port, cfgDir.c_str(), ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * Stop the CUPS subsystem / embedded responder.
 */
JNIEXPORT void JNICALL
Java_com_cuppa_cups_CupsEngine_nativeStopServer(
    JNIEnv *env,
    jobject /* this */
) {
    LOGI("nativeStopServer called");
    cuppa::CupsServer::getInstance().stop();
}

/**
 * Check if the server is currently running.
 */
JNIEXPORT jboolean JNICALL
Java_com_cuppa_cups_CupsEngine_nativeIsServerRunning(
    JNIEnv *env,
    jobject /* this */
) {
    return cuppa::CupsServer::getInstance().isRunning() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Query printer attributes using CUPS IPP client.
 */
JNIEXPORT jobject JNICALL
Java_com_cuppa_cups_CupsEngine_nativeGetPrinterAttributes(
    JNIEnv *env,
    jobject /* this */,
    jstring jUri
) {
    if (!jUri) return nullptr;
    const char *uriStr = env->GetStringUTFChars(jUri, nullptr);
    std::string uri(uriStr);
    env->ReleaseStringUTFChars(jUri, uriStr);

    LOGI("nativeGetPrinterAttributes for URI: %s", uri.c_str());

    char scheme[32] = {0};
    char userpass[64] = {0};
    char hostname[256] = {0};
    int port = 631;
    char resource[256] = {0};

    httpSeparateURI(HTTP_URI_CODING_ALL, uri.c_str(),
                    scheme, sizeof(scheme),
                    userpass, sizeof(userpass),
                    hostname, sizeof(hostname),
                    &port,
                    resource, sizeof(resource));

    if (port <= 0) port = 631;
    if (resource[0] == '\0') strncpy(resource, "/ipp/print", sizeof(resource) - 1);

    std::string printerName = "Unknown";
    std::string makeAndModel = "Generic IPP Printer";
    std::string location = "";
    std::string info = "";
    bool isDefault = false;
    bool isAcceptingJobs = true;
    int state = 3; // 3 = idle
    bool colorSupported = false;
    std::vector<std::string> formats;

    // Check if printer exists in local CUPS server or is a USB printer
    cuppa::PrinterInfo localInfo;
    if (cuppa::CupsServer::getInstance().getPrinter(uri, localInfo)) {
        LOGI("nativeGetPrinterAttributes: Found '%s' in local CUPS server registry", uri.c_str());
        printerName = localInfo.name;
        makeAndModel = localInfo.makeAndModel;
        location = localInfo.location;
        info = localInfo.info;
        state = localInfo.state;
        isAcceptingJobs = localInfo.isAcceptingJobs;
        formats = localInfo.supportedFormats;
        colorSupported = localInfo.colorSupported;
    } else if (uri.rfind("usb:", 0) == 0 || uri.rfind("usb://", 0) == 0) {
        LOGI("nativeGetPrinterAttributes: USB URI detected (%s), returning local USB printer descriptor", uri.c_str());
        printerName = "USB_Printer";
        makeAndModel = "Direct USB Thermal Printer";
        info = "USB Direct Transport";
        formats = {"application/octet-stream", "application/vnd.cups-raster", "application/pdf"};
    } else {
        char scheme[32] = {0};
        char userpass[64] = {0};
        char hostname[256] = {0};
        int port = 631;
        char resource[256] = {0};

        httpSeparateURI(HTTP_URI_CODING_ALL, uri.c_str(),
                        scheme, sizeof(scheme),
                        userpass, sizeof(userpass),
                        hostname, sizeof(hostname),
                        &port,
                        resource, sizeof(resource));

        if (port <= 0) port = 631;
        if (resource[0] == '\0') strncpy(resource, "/ipp/print", sizeof(resource) - 1);

        LOGI("nativeGetPrinterAttributes: Connecting to network IPP at %s:%d (resource: %s)", hostname, port, resource);

        http_t *http = httpConnect2(hostname, port, nullptr, AF_UNSPEC,
                                    HTTP_ENCRYPTION_IF_REQUESTED, 1, 5000, nullptr);
        if (!http) {
            LOGE("Could not connect to printer at %s:%d (%s). Checking local registry.",
                 hostname, port, cupsLastErrorString());
            if (cuppa::CupsServer::getInstance().getPrinter(uri, localInfo)) {
                printerName = localInfo.name;
                makeAndModel = localInfo.makeAndModel;
                location = localInfo.location;
                info = localInfo.info;
                state = localInfo.state;
                isAcceptingJobs = localInfo.isAcceptingJobs;
                formats = localInfo.supportedFormats;
                colorSupported = localInfo.colorSupported;
            } else {
                return nullptr;
            }
        } else {
            httpSetTimeout(http, 5.0, nullptr, nullptr);

            static const char * const pattrs[] = {
                "printer-name",
                "printer-make-and-model",
                "printer-location",
                "printer-info",
                "printer-state",
                "printer-is-accepting-jobs",
                "document-format-supported",
                "color-supported"
            };

            auto createReq = [&]() -> ipp_t* {
                ipp_t *r = ippNewRequest(IPP_OP_GET_PRINTER_ATTRIBUTES);
                ippAddString(r, IPP_TAG_OPERATION, IPP_TAG_URI, "printer-uri", nullptr, uri.c_str());
                ippAddString(r, IPP_TAG_OPERATION, IPP_TAG_NAME, "requesting-user-name", nullptr, cupsUser());
                ippAddStrings(r, IPP_TAG_OPERATION, IPP_TAG_KEYWORD, "requested-attributes",
                              sizeof(pattrs) / sizeof(pattrs[0]), nullptr, pattrs);
                return r;
            };

            ipp_t *resp = cupsDoRequest(http, createReq(), resource);

            // If initial resource failed and was default /ipp/print, retry common alternatives (/ipp, /)
            if (!resp && strcmp(resource, "/ipp/print") == 0) {
                LOGI("IPP request failed on /ipp/print, retrying with /ipp");
                resp = cupsDoRequest(http, createReq(), "/ipp");
            }
            if (!resp && (strcmp(resource, "/ipp/print") == 0 || strcmp(resource, "/ipp") == 0)) {
                LOGI("IPP request failed, retrying with root /");
                resp = cupsDoRequest(http, createReq(), "/");
            }

            if (resp) {
                ipp_attribute_t *attr;
                if ((attr = ippFindAttribute(resp, "printer-name", IPP_TAG_NAME)) != nullptr) {
                    const char *val = ippGetString(attr, 0, nullptr);
                    if (val) printerName = val;
                }
                if ((attr = ippFindAttribute(resp, "printer-make-and-model", IPP_TAG_TEXT)) != nullptr) {
                    const char *val = ippGetString(attr, 0, nullptr);
                    if (val) makeAndModel = val;
                }
                if ((attr = ippFindAttribute(resp, "printer-location", IPP_TAG_TEXT)) != nullptr) {
                    const char *val = ippGetString(attr, 0, nullptr);
                    if (val) location = val;
                }
                if ((attr = ippFindAttribute(resp, "printer-info", IPP_TAG_TEXT)) != nullptr) {
                    const char *val = ippGetString(attr, 0, nullptr);
                    if (val) info = val;
                }
                if ((attr = ippFindAttribute(resp, "printer-state", IPP_TAG_ENUM)) != nullptr) {
                    state = ippGetInteger(attr, 0);
                }
                if ((attr = ippFindAttribute(resp, "printer-is-accepting-jobs", IPP_TAG_BOOLEAN)) != nullptr) {
                    isAcceptingJobs = (ippGetBoolean(attr, 0) != 0);
                }
                if ((attr = ippFindAttribute(resp, "color-supported", IPP_TAG_BOOLEAN)) != nullptr) {
                    colorSupported = (ippGetBoolean(attr, 0) != 0);
                }
                if ((attr = ippFindAttribute(resp, "document-format-supported", IPP_TAG_MIMETYPE)) != nullptr) {
                    int count = ippGetCount(attr);
                    for (int i = 0; i < count; i++) {
                        const char *fmt = ippGetString(attr, i, nullptr);
                        if (fmt) formats.push_back(fmt);
                    }
                }
                ippDelete(resp);
            } else {
                LOGE("IPP GET_PRINTER_ATTRIBUTES failed on all endpoints: %s", cupsLastErrorString());
                httpClose(http);
                return nullptr;
            }
            httpClose(http);
        }
    }

    // Build ArrayList for supportedFormats
    jclass arrayListCls = env->FindClass("java/util/ArrayList");
    jmethodID arrayListInit = env->GetMethodID(arrayListCls, "<init>", "()V");
    jmethodID arrayListAdd = env->GetMethodID(arrayListCls, "add", "(Ljava/lang/Object;)Z");
    jobject formatsList = env->NewObject(arrayListCls, arrayListInit);

    for (const auto &fmt : formats) {
        jstring jfmt = env->NewStringUTF(fmt.c_str());
        env->CallBooleanMethod(formatsList, arrayListAdd, jfmt);
        env->DeleteLocalRef(jfmt);
    }

    jclass printerInfoCls = env->FindClass("com/cuppa/cups/PrinterInfo");
    jmethodID printerInfoInit = env->GetMethodID(
        printerInfoCls,
        "<init>",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;ZZILjava/util/List;Z)V"
    );

    jstring jName = env->NewStringUTF(printerName.c_str());
    jstring jPrinterUri = env->NewStringUTF(uri.c_str());
    jstring jModel = env->NewStringUTF(makeAndModel.c_str());
    jstring jLoc = env->NewStringUTF(location.c_str());
    jstring jInfo = env->NewStringUTF(info.c_str());

    jobject result = env->NewObject(
        printerInfoCls,
        printerInfoInit,
        jName,
        jPrinterUri,
        jModel,
        jLoc,
        jInfo,
        (jboolean)isDefault,
        (jboolean)isAcceptingJobs,
        (jint)state,
        formatsList,
        (jboolean)colorSupported
    );

    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jPrinterUri);
    env->DeleteLocalRef(jModel);
    env->DeleteLocalRef(jLoc);
    env->DeleteLocalRef(jInfo);
    env->DeleteLocalRef(formatsList);

    return result;
}

/**
 * Print a file using CUPS client cupsDoFileRequest.
 */
JNIEXPORT jint JNICALL
Java_com_cuppa_cups_CupsEngine_nativePrintFile(
    JNIEnv *env,
    jobject /* this */,
    jstring jUri,
    jstring jFilePath,
    jstring jJobTitle,
    jobject jOptions
) {
    if (!jUri || !jFilePath) return -1;

    const char *uriStr = env->GetStringUTFChars(jUri, nullptr);
    const char *filePathStr = env->GetStringUTFChars(jFilePath, nullptr);
    const char *titleStr = jJobTitle ? env->GetStringUTFChars(jJobTitle, nullptr) : "Cuppa Print Job";

    std::string uri(uriStr);
    std::string filePath(filePathStr);
    std::string title(titleStr);

    env->ReleaseStringUTFChars(jUri, uriStr);
    env->ReleaseStringUTFChars(jFilePath, filePathStr);
    if (jJobTitle) env->ReleaseStringUTFChars(jJobTitle, titleStr);

    LOGI("nativePrintFile: uri=%s, file=%s, title=%s", uri.c_str(), filePath.c_str(), title.c_str());

    char scheme[32] = {0};
    char userpass[64] = {0};
    char hostname[256] = {0};
    int port = 631;
    char resource[256] = {0};

    httpSeparateURI(HTTP_URI_CODING_ALL, uri.c_str(),
                    scheme, sizeof(scheme),
                    userpass, sizeof(userpass),
                    hostname, sizeof(hostname),
                    &port,
                    resource, sizeof(resource));

    if (port <= 0) port = 631;
    if (resource[0] == '\0') strncpy(resource, "/ipp/print", sizeof(resource) - 1);

    LOGI("nativePrintFile: calling httpConnect2(%s:%d)...", hostname, port);
    http_t *http = httpConnect2(hostname, port, nullptr, AF_UNSPEC,
                                HTTP_ENCRYPTION_IF_REQUESTED, 1, 10000, nullptr);
    if (!http) {
        LOGE("nativePrintFile: Failed to connect to %s:%d (%s)", hostname, port, cupsLastErrorString());
        return -1;
    }
    LOGI("nativePrintFile: httpConnect2 succeeded, resource=%s", resource);
    // An uncompressed color PWG-Raster page at 300dpi can easily be 20-30MB (e.g. a Letter page
    // is ~25MB); confirmed live that a real successful print over TLS took ~15s to transfer and
    // process, which the previous 15s timeout treated as a failure despite the printer actually
    // completing the job. 90s gives real jobs comfortable headroom without waiting forever on a
    // genuinely dead connection.
    httpSetTimeout(http, 90.0, nullptr, nullptr);

    // Sniff the actual document type from its magic bytes so the target printer knows what it's
    // receiving. Leaving document-format unset makes most IPP daemons assume a default (often
    // "application/octet-stream"), and a real printer fed bytes in a format it doesn't recognize
    // can stall trying to parse them rather than rejecting the job cleanly — this was observed
    // live against a real Epson network printer sent a raw PCL stream it never advertised support
    // for (see PrintJobDispatcher/TestPrintSheet: network targets are now restricted to formats a
    // printer's own advertised capabilities cover, but declaring the real format here is correct
    // regardless of what's sent).
    const char *documentFormat = "application/octet-stream";
    {
        std::ifstream sniff(filePath, std::ios::binary);
        char magic[5] = {0};
        if (sniff.read(magic, 4)) {
            if (memcmp(magic, "%PDF", 4) == 0) {
                documentFormat = "application/pdf";
            } else if (magic[0] == '%' && magic[1] == '!') {
                documentFormat = "application/postscript";
            }
        }
    }

    ipp_t *req = ippNewRequest(IPP_OP_PRINT_JOB);
    ippAddString(req, IPP_TAG_OPERATION, IPP_TAG_URI, "printer-uri", nullptr, uri.c_str());
    ippAddString(req, IPP_TAG_OPERATION, IPP_TAG_NAME, "requesting-user-name", nullptr, cupsUser());
    ippAddString(req, IPP_TAG_OPERATION, IPP_TAG_NAME, "job-name", nullptr, title.c_str());
    ippAddString(req, IPP_TAG_OPERATION, IPP_TAG_MIMETYPE, "document-format", nullptr, documentFormat);

    // Extract options from Map<String, String> if provided
    if (jOptions) {
        jclass mapCls = env->GetObjectClass(jOptions);
        jmethodID entrySetMethod = env->GetMethodID(mapCls, "entrySet", "()Ljava/util/Set;");
        jobject entrySet = env->CallObjectMethod(jOptions, entrySetMethod);
        jclass setCls = env->GetObjectClass(entrySet);
        jmethodID iteratorMethod = env->GetMethodID(setCls, "iterator", "()Ljava/util/Iterator;");
        jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);
        jclass iterCls = env->GetObjectClass(iterator);
        jmethodID hasNextMethod = env->GetMethodID(iterCls, "hasNext", "()Z");
        jmethodID nextMethod = env->GetMethodID(iterCls, "next", "()Ljava/lang/Object;");

        jclass entryCls = env->FindClass("java/util/Map$Entry");
        jmethodID getKeyMethod = env->GetMethodID(entryCls, "getKey", "()Ljava/lang/Object;");
        jmethodID getValueMethod = env->GetMethodID(entryCls, "getValue", "()Ljava/lang/Object;");

        while (env->CallBooleanMethod(iterator, hasNextMethod)) {
            jobject entry = env->CallObjectMethod(iterator, nextMethod);
            jstring jKey = (jstring)env->CallObjectMethod(entry, getKeyMethod);
            jstring jVal = (jstring)env->CallObjectMethod(entry, getValueMethod);

            const char *keyStr = env->GetStringUTFChars(jKey, nullptr);
            const char *valStr = env->GetStringUTFChars(jVal, nullptr);

            ippAddString(req, IPP_TAG_JOB, IPP_TAG_KEYWORD, keyStr, nullptr, valStr);

            env->ReleaseStringUTFChars(jKey, keyStr);
            env->ReleaseStringUTFChars(jVal, valStr);
            env->DeleteLocalRef(entry);
            env->DeleteLocalRef(jKey);
            env->DeleteLocalRef(jVal);
        }

        env->DeleteLocalRef(iterator);
        env->DeleteLocalRef(entrySet);
    }

    ipp_t *resp = cupsDoFileRequest(http, req, resource, filePath.c_str());
    int jobId = -1;
    if (resp) {
        ipp_status_t ippStatus = ippGetStatusCode(resp);
        // IPP_TAG_ZERO matches "job-id" regardless of its actual value tag — some printers
        // don't use the exact IPP_TAG_INTEGER tag CUPS's own attributes use, and requiring an
        // exact match meant a genuinely successful submission (IPP status OK, real job-id
        // present) was being reported as a failure (-1) just because of a tag mismatch.
        ipp_attribute_t *attr = ippFindAttribute(resp, "job-id", IPP_TAG_ZERO);
        if (attr) {
            jobId = ippGetInteger(attr, 0);
        } else if (ippStatus < IPP_STATUS_ERROR_BAD_REQUEST) {
            // Request succeeded at the IPP level but the printer didn't echo a job-id back —
            // still real success, so don't report it as a spool failure.
            jobId = 1;
        }
        LOGI("nativePrintFile: Print-Job response: ipp-status=0x%04x, jobId=%d", (int)ippStatus, jobId);
        ippDelete(resp);
    } else {
        LOGE("nativePrintFile: Print-Job failed: %s", cupsLastErrorString());
    }

    httpClose(http);
    return jobId;
}

/**
 * Query jobs from printer via IPP GET_JOBS or local server.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_cuppa_cups_CupsEngine_nativeGetJobs(
    JNIEnv *env,
    jobject /* this */,
    jstring jUri
) {
    jclass printJobCls = env->FindClass("com/cuppa/cups/PrintJob");
    jmethodID printJobInit = env->GetMethodID(
        printJobCls,
        "<init>",
        "(ILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;IJJLjava/lang/String;)V"
    );

    std::string uri;
    if (jUri) {
        const char *uriStr = env->GetStringUTFChars(jUri, nullptr);
        if (uriStr) {
            uri = uriStr;
            env->ReleaseStringUTFChars(jUri, uriStr);
        }
    }

    struct JobData {
        int id;
        std::string name;
        std::string printerUri;
        std::string user;
        std::string format;
        int state;
        long size;
        long createdAt;
        std::string spoolFilePath;
    };
    std::vector<JobData> jobsList;

    if (!uri.empty() && (uri.rfind("ipp://", 0) == 0 || uri.rfind("http://", 0) == 0)) {
        char scheme[32] = {0};
        char userpass[64] = {0};
        char hostname[256] = {0};
        int port = 631;
        char resource[256] = {0};

        httpSeparateURI(HTTP_URI_CODING_ALL, uri.c_str(),
                        scheme, sizeof(scheme),
                        userpass, sizeof(userpass),
                        hostname, sizeof(hostname),
                        &port,
                        resource, sizeof(resource));
        if (port <= 0) port = 631;
        if (resource[0] == '\0') strncpy(resource, "/ipp/print", sizeof(resource) - 1);

        http_t *http = httpConnect2(hostname, port, nullptr, AF_UNSPEC,
                                    HTTP_ENCRYPTION_IF_REQUESTED, 1, 3000, nullptr);
        if (http) {
            httpSetTimeout(http, 3.0, nullptr, nullptr);
            ipp_t *req = ippNewRequest(IPP_OP_GET_JOBS);
            ippAddString(req, IPP_TAG_OPERATION, IPP_TAG_URI, "printer-uri", nullptr, uri.c_str());
            ippAddString(req, IPP_TAG_OPERATION, IPP_TAG_KEYWORD, "which-jobs", nullptr, "all");

            ipp_t *resp = cupsDoRequest(http, req, resource);
            if (resp) {
                for (ipp_attribute_t *attr = ippFirstAttribute(resp); attr != nullptr; attr = ippNextAttribute(resp)) {
                    while (attr && ippGetGroupTag(attr) != IPP_TAG_JOB) {
                        attr = ippNextAttribute(resp);
                    }
                    if (!attr) break;

                    JobData jd;
                    jd.id = 0;
                    jd.name = "Untitled";
                    jd.printerUri = uri;
                    jd.user = "anonymous";
                    jd.format = "application/octet-stream";
                    jd.state = 9; // completed
                    jd.size = 0;
                    jd.createdAt = 0;
                    jd.spoolFilePath = "";

                    for (; attr && ippGetGroupTag(attr) == IPP_TAG_JOB; attr = ippNextAttribute(resp)) {
                        const char *aname = ippGetName(attr);
                        if (!aname) continue;
                        if (strcmp(aname, "job-id") == 0) {
                            jd.id = ippGetInteger(attr, 0);
                        } else if (strcmp(aname, "job-name") == 0) {
                            const char *s = ippGetString(attr, 0, nullptr);
                            if (s) jd.name = s;
                        } else if (strcmp(aname, "job-originating-user-name") == 0) {
                            const char *s = ippGetString(attr, 0, nullptr);
                            if (s) jd.user = s;
                        } else if (strcmp(aname, "document-format") == 0) {
                            const char *s = ippGetString(attr, 0, nullptr);
                            if (s) jd.format = s;
                        } else if (strcmp(aname, "job-state") == 0) {
                            jd.state = ippGetInteger(attr, 0);
                        } else if (strcmp(aname, "job-k-octets") == 0) {
                            jd.size = (long)ippGetInteger(attr, 0) * 1024L;
                        } else if (strcmp(aname, "time-at-creation") == 0) {
                            jd.createdAt = (long)ippGetInteger(attr, 0) * 1000L;
                        }
                    }

                    if (jd.id > 0) {
                        jobsList.push_back(jd);
                    }
                    if (!attr) break;
                }
                ippDelete(resp);
            }
            httpClose(http);
        }
    }

    // Also include local server jobs
    auto localJobs = cuppa::CupsServer::getInstance().getJobs();
    for (const auto &lj : localJobs) {
        JobData jd;
        jd.id = lj.jobId;
        jd.name = lj.jobName;
        jd.printerUri = lj.printerName;
        jd.user = lj.user;
        jd.format = lj.documentFormat;
        jd.state = lj.state;
        jd.size = (long)lj.dataSize;
        jd.createdAt = lj.createdAt;
        jd.spoolFilePath = lj.spoolFilePath;
        jobsList.push_back(jd);
    }

    jobjectArray resultArr = env->NewObjectArray((jsize)jobsList.size(), printJobCls, nullptr);
    for (size_t i = 0; i < jobsList.size(); i++) {
        const auto &jd = jobsList[i];
        jstring jName = env->NewStringUTF(jd.name.c_str());
        jstring jPrinterUri = env->NewStringUTF(jd.printerUri.c_str());
        jstring jUser = env->NewStringUTF(jd.user.c_str());
        jstring jFormat = env->NewStringUTF(jd.format.c_str());
        jstring jSpool = env->NewStringUTF(jd.spoolFilePath.c_str());

        jobject jJob = env->NewObject(
            printJobCls,
            printJobInit,
            (jint)jd.id,
            jName,
            jPrinterUri,
            jUser,
            jFormat,
            (jint)jd.state,
            (jlong)jd.size,
            (jlong)jd.createdAt,
            jSpool
        );

        env->SetObjectArrayElement(resultArr, (jsize)i, jJob);

        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jPrinterUri);
        env->DeleteLocalRef(jUser);
        env->DeleteLocalRef(jFormat);
        env->DeleteLocalRef(jSpool);
        env->DeleteLocalRef(jJob);
    }

    return resultArr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_cuppa_cups_CupsEngine_nativeProcessIppRequest(
    JNIEnv *env,
    jobject /* this */,
    jbyteArray jRequest
) {
    if (!jRequest) return nullptr;

    jsize len = env->GetArrayLength(jRequest);
    if (len == 0) return nullptr;

    jbyte *bytes = env->GetByteArrayElements(jRequest, nullptr);
    std::vector<uint8_t> responseBytes = cuppa::CupsServer::getInstance().processIppRequest(
        reinterpret_cast<const uint8_t*>(bytes), (size_t)len);
    env->ReleaseByteArrayElements(jRequest, bytes, JNI_ABORT);

    if (responseBytes.empty()) return nullptr;

    jbyteArray jResponse = env->NewByteArray(responseBytes.size());
    env->SetByteArrayRegion(jResponse, 0, responseBytes.size(), reinterpret_cast<const jbyte*>(responseBytes.data()));
    return jResponse;
}

JNIEXPORT void JNICALL
Java_com_cuppa_cups_CupsEngine_nativeUpdateJobState(
    JNIEnv *env,
    jobject /* this */,
    jint jobId,
    jint state
) {
    cuppa::CupsServer::getInstance().updateJobState((int32_t)jobId, (int32_t)state);
}

JNIEXPORT jboolean JNICALL
Java_com_cuppa_cups_CupsEngine_nativeAddPrinter(
    JNIEnv *env,
    jobject /* this */,
    jobject jPrinterInfo
) {
    if (!jPrinterInfo) return JNI_FALSE;

    jclass cls = env->GetObjectClass(jPrinterInfo);

    jmethodID getName = env->GetMethodID(cls, "getName", "()Ljava/lang/String;");
    jmethodID getUri = env->GetMethodID(cls, "getUri", "()Ljava/lang/String;");
    jmethodID getModel = env->GetMethodID(cls, "getMakeAndModel", "()Ljava/lang/String;");
    jmethodID getLocation = env->GetMethodID(cls, "getLocation", "()Ljava/lang/String;");
    jmethodID getInfo = env->GetMethodID(cls, "getInfo", "()Ljava/lang/String;");
    jmethodID isDefault = env->GetMethodID(cls, "isDefault", "()Z");
    jmethodID isAccepting = env->GetMethodID(cls, "isAcceptingJobs", "()Z");
    jmethodID getState = env->GetMethodID(cls, "getState", "()I");
    jmethodID getColor = env->GetMethodID(cls, "getColorSupported", "()Z");
    jmethodID getFormats = env->GetMethodID(cls, "getSupportedFormats", "()Ljava/util/List;");

    jstring jName = (jstring)env->CallObjectMethod(jPrinterInfo, getName);
    jstring jUri = (jstring)env->CallObjectMethod(jPrinterInfo, getUri);
    jstring jModel = (jstring)env->CallObjectMethod(jPrinterInfo, getModel);
    jstring jLoc = (jstring)env->CallObjectMethod(jPrinterInfo, getLocation);
    jstring jInf = (jstring)env->CallObjectMethod(jPrinterInfo, getInfo);

    const char *nameStr = jName ? env->GetStringUTFChars(jName, nullptr) : "";
    const char *uriStr = jUri ? env->GetStringUTFChars(jUri, nullptr) : "";
    const char *modelStr = jModel ? env->GetStringUTFChars(jModel, nullptr) : "";
    const char *locStr = jLoc ? env->GetStringUTFChars(jLoc, nullptr) : "";
    const char *infoStr = jInf ? env->GetStringUTFChars(jInf, nullptr) : "";

    cuppa::PrinterInfo pinfo;
    pinfo.name = nameStr ? nameStr : "";
    pinfo.uri = uriStr ? uriStr : "";
    pinfo.makeAndModel = modelStr ? modelStr : "";
    pinfo.location = locStr ? locStr : "";
    pinfo.info = infoStr ? infoStr : "";
    pinfo.isDefault = env->CallBooleanMethod(jPrinterInfo, isDefault);
    pinfo.isAcceptingJobs = env->CallBooleanMethod(jPrinterInfo, isAccepting);
    pinfo.state = env->CallIntMethod(jPrinterInfo, getState);
    pinfo.colorSupported = env->CallBooleanMethod(jPrinterInfo, getColor);

    if (jName) env->ReleaseStringUTFChars(jName, nameStr);
    if (jUri) env->ReleaseStringUTFChars(jUri, uriStr);
    if (jModel) env->ReleaseStringUTFChars(jModel, modelStr);
    if (jLoc) env->ReleaseStringUTFChars(jLoc, locStr);
    if (jInf) env->ReleaseStringUTFChars(jInf, infoStr);

    // Extract formats list
    jobject jFormatsList = env->CallObjectMethod(jPrinterInfo, getFormats);
    if (jFormatsList) {
        jclass listCls = env->GetObjectClass(jFormatsList);
        jmethodID sizeMethod = env->GetMethodID(listCls, "size", "()I");
        jmethodID getMethod = env->GetMethodID(listCls, "get", "(I)Ljava/lang/Object;");
        jint size = env->CallIntMethod(jFormatsList, sizeMethod);
        if (size > 0) {
            pinfo.supportedFormats.clear();
            for (jint i = 0; i < size; i++) {
                jstring fmtStr = (jstring)env->CallObjectMethod(jFormatsList, getMethod, i);
                if (fmtStr) {
                    const char *cStr = env->GetStringUTFChars(fmtStr, nullptr);
                    if (cStr) {
                        pinfo.supportedFormats.push_back(cStr);
                        env->ReleaseStringUTFChars(fmtStr, cStr);
                    }
                    env->DeleteLocalRef(fmtStr);
                }
            }
        }
        env->DeleteLocalRef(jFormatsList);
        env->DeleteLocalRef(listCls);
    }

    if (jName) env->DeleteLocalRef(jName);
    if (jUri) env->DeleteLocalRef(jUri);
    if (jModel) env->DeleteLocalRef(jModel);
    if (jLoc) env->DeleteLocalRef(jLoc);
    if (jInf) env->DeleteLocalRef(jInf);
    env->DeleteLocalRef(cls);

    bool ok = cuppa::CupsServer::getInstance().addPrinter(pinfo);
    LOGI("nativeAddPrinter: %s (result=%d)", pinfo.name.c_str(), ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_cuppa_cups_CupsEngine_nativeRemovePrinter(
    JNIEnv *env,
    jobject /* this */,
    jstring jName
) {
    if (!jName) return JNI_FALSE;
    const char *nameStr = env->GetStringUTFChars(jName, nullptr);
    std::string name(nameStr ? nameStr : "");
    env->ReleaseStringUTFChars(jName, nameStr);

    bool ok = cuppa::CupsServer::getInstance().removePrinter(name);
    LOGI("nativeRemovePrinter: %s (result=%d)", name.c_str(), ok);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_cuppa_cups_CupsEngine_nativeClearPrinters(
    JNIEnv * /* env */,
    jobject /* this */
) {
    cuppa::CupsServer::getInstance().clearPrinters();
    LOGI("nativeClearPrinters called");
}

JNIEXPORT void JNICALL
Java_com_cuppa_cups_CupsEngine_nativeSetServerHost(
    JNIEnv *env,
    jobject /* this */,
    jstring jHost
) {
    if (!jHost) return;
    const char *hostStr = env->GetStringUTFChars(jHost, nullptr);
    std::string host(hostStr ? hostStr : "");
    env->ReleaseStringUTFChars(jHost, hostStr);

    cuppa::CupsServer::getInstance().setHost(host);
    LOGI("nativeSetServerHost: %s", host.c_str());
}

/**
 * Encode a single rendered page as a PWG-Raster document — the format IPP Everywhere / AirPrint
 * mandates and that most real network printers (including ones with no PDF interpreter on board,
 * like a consumer Epson inkjet) actually understand. Uses the genuine CUPS raster writer already
 * linked into this library (cups/raster-stream.c via the public wrappers in cups/raster-stubs.c)
 * rather than hand-rolling the binary format.
 *
 * @param jRgbPixels Packed top-to-bottom pixel rows, no padding: 3 bytes/pixel (R,G,B) if
 *                   colorMode, or 1 byte/pixel (gray) otherwise. Exactly width*height*bpp bytes.
 * @param width/height Page raster dimensions in pixels (must match what rgbPixels contains).
 * @param dpi Resolution the page was rendered at.
 * @param colorMode true for srgb_8, false for sgray_8.
 * @param jOutputPath Destination file for the encoded .ras document.
 *
 * Single-page only: a real multi-page PWG-Raster stream needs one sync header for the whole
 * stream followed by consecutive page blocks written on the same still-open cups_raster_t, which
 * would need this call to stay open across multiple JNI calls. Not needed yet — the current
 * caller (network test prints) only ever generates one-page documents — but a real multi-page
 * document sent this way would only contain its first page.
 */
JNIEXPORT jboolean JNICALL
Java_com_cuppa_cups_CupsEngine_nativeEncodePwgRasterPage(
    JNIEnv *env,
    jobject /* this */,
    jbyteArray jRgbPixels,
    jint width,
    jint height,
    jint dpi,
    jboolean colorMode,
    jstring jOutputPath
) {
    if (!jRgbPixels || !jOutputPath || width <= 0 || height <= 0 || dpi <= 0) return JNI_FALSE;

    const char *outputPath = env->GetStringUTFChars(jOutputPath, nullptr);
    std::string path(outputPath ? outputPath : "");
    env->ReleaseStringUTFChars(jOutputPath, outputPath);
    if (path.empty()) return JNI_FALSE;

    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        LOGE("nativeEncodePwgRasterPage: failed to open %s for writing (errno=%d)", path.c_str(), errno);
        return JNI_FALSE;
    }

    cups_raster_t *ras = cupsRasterOpen(fd, CUPS_RASTER_WRITE_PWG);
    if (!ras) {
        LOGE("nativeEncodePwgRasterPage: cupsRasterOpen failed: %s", cupsRasterErrorString());
        close(fd);
        return JNI_FALSE;
    }

    // PWG media dimensions are in 1/2540 inch units; derive them from the actual pixel
    // dimensions and dpi so the header's own width/height calc lands exactly on what we have.
    pwg_media_t *media = pwgMediaForSize((int)((double)width * 2540.0 / dpi + 0.5),
                                         (int)((double)height * 2540.0 / dpi + 0.5));
    if (!media) {
        LOGE("nativeEncodePwgRasterPage: pwgMediaForSize failed for %dx%d @ %d dpi", width, height, dpi);
        cupsRasterClose(ras);
        close(fd);
        return JNI_FALSE;
    }

    cups_page_header2_t header;
    const char *type = colorMode ? "srgb_8" : "sgray_8";
    if (!cupsRasterInitPWGHeader(&header, media, type, dpi, dpi, "one-sided", nullptr)) {
        LOGE("nativeEncodePwgRasterPage: cupsRasterInitPWGHeader failed: %s", cupsRasterErrorString());
        cupsRasterClose(ras);
        close(fd);
        return JNI_FALSE;
    }

    // Override with the exact bitmap dimensions (media-derived rounding could be off by a pixel
    // or two) so the byte count we write always matches what the header declares.
    header.cupsWidth       = (unsigned)width;
    header.cupsHeight      = (unsigned)height;
    header.cupsBytesPerLine = (header.cupsWidth * header.cupsBitsPerPixel + 7) / 8;

    if (!cupsRasterWriteHeader2(ras, &header)) {
        LOGE("nativeEncodePwgRasterPage: cupsRasterWriteHeader2 failed: %s", cupsRasterErrorString());
        cupsRasterClose(ras);
        close(fd);
        return JNI_FALSE;
    }

    jsize pixelBytes = env->GetArrayLength(jRgbPixels);
    jsize expectedBytes = (jsize)header.cupsBytesPerLine * height;
    if (pixelBytes < expectedBytes) {
        LOGE("nativeEncodePwgRasterPage: pixel buffer too small (%d bytes, need %d)", (int)pixelBytes, (int)expectedBytes);
        cupsRasterClose(ras);
        close(fd);
        return JNI_FALSE;
    }

    jbyte *pixels = env->GetByteArrayElements(jRgbPixels, nullptr);
    bool writeOk = true;
    for (unsigned y = 0; y < header.cupsHeight && writeOk; y++) {
        unsigned char *row = (unsigned char *)(pixels + (size_t)y * header.cupsBytesPerLine);
        if (!cupsRasterWritePixels(ras, row, header.cupsBytesPerLine)) {
            LOGE("nativeEncodePwgRasterPage: cupsRasterWritePixels failed at row %u: %s", y, cupsRasterErrorString());
            writeOk = false;
        }
    }
    env->ReleaseByteArrayElements(jRgbPixels, pixels, JNI_ABORT);

    cupsRasterClose(ras);
    close(fd);

    LOGI("nativeEncodePwgRasterPage: wrote %dx%d %s page (%u bytes/line) to %s (ok=%d)",
         width, height, type, header.cupsBytesPerLine, path.c_str(), writeOk);
    return writeOk ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
