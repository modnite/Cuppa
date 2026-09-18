#ifndef CUPS_IPP_H
#define CUPS_IPP_H

#include <cstdint>
#include <string>
#include <vector>
#include <map>
#include <memory>

namespace cuppa {

// Standard IPP Value Tags (RFC 8010)
enum class IppTag : uint8_t {
    DELIMITER_ZERO = 0x00,
    OPERATION_ATTRIBUTES = 0x01,
    JOB_ATTRIBUTES = 0x02,
    END_OF_ATTRIBUTES = 0x03,
    PRINTER_ATTRIBUTES = 0x04,
    UNSUPPORTED_ATTRIBUTES = 0x05,

    // Value tags
    INTEGER = 0x21,
    BOOLEAN = 0x22,
    ENUM = 0x23,
    OCTET_STRING = 0x30,
    DATE_TIME = 0x31,
    RESOLUTION = 0x32,
    RANGE_OF_INTEGER = 0x33,
    BEG_COLLECTION = 0x34,
    END_COLLECTION = 0x37,
    MEMBER_ATTR_NAME = 0x4A,
    TEXT_WITH_LANGUAGE = 0x35,
    NAME_WITH_LANGUAGE = 0x36,
    TEXT_WITHOUT_LANGUAGE = 0x41,
    NAME_WITHOUT_LANGUAGE = 0x42,
    KEYWORD = 0x44,
    URI = 0x45,
    URI_SCHEME = 0x46,
    CHARSET = 0x47,
    NATURAL_LANGUAGE = 0x48,
    MIME_MEDIA_TYPE = 0x49
};

// IPP Operations (RFC 8011)
enum class IppOp : uint16_t {
    PRINT_JOB = 0x0002,
    PRINT_URI = 0x0003,
    VALIDATE_JOB = 0x0004,
    CREATE_JOB = 0x0005,
    SEND_DOCUMENT = 0x0006,
    SEND_URI = 0x0007,
    CANCEL_JOB = 0x0008,
    GET_JOB_ATTRIBUTES = 0x0009,
    GET_JOBS = 0x000A,
    GET_PRINTER_ATTRIBUTES = 0x000B,
    HOLD_JOB = 0x000C,
    RELEASE_JOB = 0x000D,
    RESTART_JOB = 0x000E,
    PAUSE_PRINTER = 0x0010,
    RESUME_PRINTER = 0x0011,
    PURGE_JOBS = 0x0012,
    CUPS_GET_DEFAULT = 0x4001,
    CUPS_GET_PRINTERS = 0x4002,
    CUPS_ADD_MODIFY_PRINTER = 0x4003,
    CUPS_DELETE_PRINTER = 0x4004
};

// IPP Status Codes (RFC 8011)
enum class IppStatus : uint16_t {
    OK = 0x0000,
    OK_SUBST = 0x0001,
    OK_CONFLICT = 0x0002,
    CLIENT_BAD_REQUEST = 0x0400,
    CLIENT_FORBIDDEN = 0x0401,
    CLIENT_NOT_AUTHENTICATED = 0x0402,
    CLIENT_NOT_AUTHORIZED = 0x0403,
    CLIENT_NOT_POSSIBLE = 0x0404,
    CLIENT_TIMEOUT = 0x0405,
    CLIENT_NOT_FOUND = 0x0406,
    CLIENT_GONE = 0x0407,
    CLIENT_REQUEST_ENTITY_TOO_LONG = 0x0408,
    CLIENT_REQUEST_VALUE_TOO_LONG = 0x0409,
    CLIENT_DOCUMENT_FORMAT_NOT_SUPPORTED = 0x040A,
    CLIENT_ATTRIBUTES_NOT_SUPPORTED = 0x040B,
    SERVER_INTERNAL_ERROR = 0x0500,
    SERVER_OPERATION_NOT_SUPPORTED = 0x0501,
    SERVER_SERVICE_UNAVAILABLE = 0x0502,
    SERVER_VERSION_NOT_SUPPORTED = 0x0503,
    SERVER_DEVICE_ERROR = 0x0504,
    SERVER_TEMPORARY_ERROR = 0x0505,
    SERVER_NOT_ACCEPTING_JOBS = 0x0506,
    SERVER_BUSY = 0x0507
};

struct IppAttribute {
    IppTag tag;
    std::string name;
    std::vector<uint8_t> valueBytes;

    // Helpers
    std::string asString() const {
        return std::string(valueBytes.begin(), valueBytes.end());
    }

    int32_t asInt() const {
        if (valueBytes.size() >= 4) {
            return (static_cast<int32_t>(valueBytes[0]) << 24) |
                   (static_cast<int32_t>(valueBytes[1]) << 16) |
                   (static_cast<int32_t>(valueBytes[2]) << 8) |
                   static_cast<int32_t>(valueBytes[3]);
        }
        return 0;
    }

    bool asBool() const {
        return !valueBytes.empty() && valueBytes[0] != 0;
    }
};

struct IppGroup {
    IppTag groupTag;
    std::vector<IppAttribute> attributes;
};

class IppMessage {
public:
    uint16_t version = 0x0200; // IPP 2.0 default
    union {
        IppOp operation;
        IppStatus status;
        uint16_t code = 0;
    };
    uint32_t requestId = 1;

    std::vector<IppGroup> groups;
    std::vector<uint8_t> documentData;

    IppMessage() : code(0) {}

    static std::shared_ptr<IppMessage> parse(const uint8_t *data, size_t size);
    std::vector<uint8_t> encode() const;

    void addAttribute(IppTag groupTag, IppTag valueTag, const std::string &name, const std::string &value);
    void addIntAttribute(IppTag groupTag, IppTag valueTag, const std::string &name, int32_t value);
    void addBoolAttribute(IppTag groupTag, const std::string &name, bool value);
    void addResolutionAttribute(IppTag groupTag, const std::string &name, int32_t xres, int32_t yres, uint8_t units);
    void addRangeAttribute(IppTag groupTag, const std::string &name, int32_t lower, int32_t upper);
    void addDateTimeAttribute(IppTag groupTag, const std::string &name, int64_t epochSeconds);
    // Collections (RFC 8010 3.1.6): open with beginCollection, add members with addCollectionMember*,
    // close with endCollection. Pass an empty name for the 2nd+ value of a 1setOf collection.
    void beginCollection(IppTag groupTag, const std::string &name);
    void endCollection(IppTag groupTag);
    void addCollectionMemberName(IppTag groupTag, const std::string &member);

    const IppAttribute* findAttribute(const std::string &name) const;
    std::string getPrinterUri() const;
    std::string getJobName() const;
    std::string getRequestingUserName() const;
};

} // namespace cuppa

#endif // CUPS_IPP_H
