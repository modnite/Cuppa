#include "cups_ipp.h"
#include <cstring>
#include <ctime>
#include <android/log.h>

#define LOG_TAG "CuppaIPP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace cuppa {

static inline uint16_t readU16(const uint8_t *p) {
    return (static_cast<uint16_t>(p[0]) << 8) | static_cast<uint16_t>(p[1]);
}

static inline uint32_t readU32(const uint8_t *p) {
    return (static_cast<uint32_t>(p[0]) << 24) |
           (static_cast<uint32_t>(p[1]) << 16) |
           (static_cast<uint32_t>(p[2]) << 8) |
           static_cast<uint32_t>(p[3]);
}

static inline void writeU16(std::vector<uint8_t> &buf, uint16_t val) {
    buf.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(val & 0xFF));
}

static inline void writeU32(std::vector<uint8_t> &buf, uint32_t val) {
    buf.push_back(static_cast<uint8_t>((val >> 24) & 0xFF));
    buf.push_back(static_cast<uint8_t>((val >> 16) & 0xFF));
    buf.push_back(static_cast<uint8_t>((val >> 8) & 0xFF));
    buf.push_back(static_cast<uint8_t>(val & 0xFF));
}

std::shared_ptr<IppMessage> IppMessage::parse(const uint8_t *data, size_t size) {
    if (size < 8) {
        LOGE("IPP buffer too short: %zu bytes", size);
        return nullptr;
    }

    auto msg = std::make_shared<IppMessage>();
    msg->version = readU16(data);
    msg->code = readU16(data + 2);
    msg->requestId = readU32(data + 4);

    size_t offset = 8;
    IppGroup *currentGroup = nullptr;
    std::string lastName;

    while (offset < size) {
        uint8_t tag = data[offset++];

        // Check if delimiter tag
        if (tag == static_cast<uint8_t>(IppTag::END_OF_ATTRIBUTES)) {
            // Remainder of the buffer is document payload
            if (offset < size) {
                msg->documentData.assign(data + offset, data + size);
            }
            break;
        }

        if (tag >= 0x01 && tag <= 0x0F) {
            // Attribute group delimiter tag
            msg->groups.push_back(IppGroup{static_cast<IppTag>(tag), {}});
            currentGroup = &msg->groups.back();
            continue;
        }

        // Value tag
        if (!currentGroup) {
            msg->groups.push_back(IppGroup{IppTag::OPERATION_ATTRIBUTES, {}});
            currentGroup = &msg->groups.back();
        }

        if (offset + 2 > size) break;
        uint16_t nameLen = readU16(data + offset);
        offset += 2;

        std::string attrName;
        if (nameLen > 0) {
            if (offset + nameLen > size) break;
            attrName.assign(reinterpret_cast<const char*>(data + offset), nameLen);
            offset += nameLen;
            lastName = attrName;
        } else {
            // 1setOf attribute: same name as previous attribute
            attrName = lastName;
        }

        if (offset + 2 > size) break;
        uint16_t valueLen = readU16(data + offset);
        offset += 2;

        std::vector<uint8_t> valBytes;
        if (valueLen > 0) {
            if (offset + valueLen > size) break;
            valBytes.assign(data + offset, data + offset + valueLen);
            offset += valueLen;
        }

        currentGroup->attributes.push_back(IppAttribute{
            static_cast<IppTag>(tag),
            attrName,
            std::move(valBytes)
        });
    }

    return msg;
}

std::vector<uint8_t> IppMessage::encode() const {
    std::vector<uint8_t> buf;
    buf.reserve(1024 + documentData.size());

    writeU16(buf, version);
    writeU16(buf, code);
    writeU32(buf, requestId);

    for (const auto &group : groups) {
        buf.push_back(static_cast<uint8_t>(group.groupTag));
        std::string lastWrittenName;

        for (const auto &attr : group.attributes) {
            buf.push_back(static_cast<uint8_t>(attr.tag));

            if (attr.name == lastWrittenName && !attr.name.empty()) {
                // 1setOf element: empty name length
                writeU16(buf, 0);
            } else {
                writeU16(buf, static_cast<uint16_t>(attr.name.size()));
                buf.insert(buf.end(), attr.name.begin(), attr.name.end());
                lastWrittenName = attr.name;
            }

            writeU16(buf, static_cast<uint16_t>(attr.valueBytes.size()));
            buf.insert(buf.end(), attr.valueBytes.begin(), attr.valueBytes.end());
        }
    }

    buf.push_back(static_cast<uint8_t>(IppTag::END_OF_ATTRIBUTES));

    if (!documentData.empty()) {
        buf.insert(buf.end(), documentData.begin(), documentData.end());
    }

    return buf;
}

void IppMessage::addAttribute(IppTag groupTag, IppTag valueTag, const std::string &name, const std::string &value) {
    IppGroup *targetGroup = nullptr;
    for (auto &g : groups) {
        if (g.groupTag == groupTag) {
            targetGroup = &g;
            break;
        }
    }
    if (!targetGroup) {
        groups.push_back(IppGroup{groupTag, {}});
        targetGroup = &groups.back();
    }

    std::vector<uint8_t> val(value.begin(), value.end());
    targetGroup->attributes.push_back(IppAttribute{valueTag, name, std::move(val)});
}

void IppMessage::addIntAttribute(IppTag groupTag, IppTag valueTag, const std::string &name, int32_t value) {
    IppGroup *targetGroup = nullptr;
    for (auto &g : groups) {
        if (g.groupTag == groupTag) {
            targetGroup = &g;
            break;
        }
    }
    if (!targetGroup) {
        groups.push_back(IppGroup{groupTag, {}});
        targetGroup = &groups.back();
    }

    std::vector<uint8_t> val = {
        static_cast<uint8_t>((value >> 24) & 0xFF),
        static_cast<uint8_t>((value >> 16) & 0xFF),
        static_cast<uint8_t>((value >> 8) & 0xFF),
        static_cast<uint8_t>(value & 0xFF)
    };
    targetGroup->attributes.push_back(IppAttribute{valueTag, name, std::move(val)});
}

void IppMessage::addBoolAttribute(IppTag groupTag, const std::string &name, bool value) {
    IppGroup *targetGroup = nullptr;
    for (auto &g : groups) {
        if (g.groupTag == groupTag) {
            targetGroup = &g;
            break;
        }
    }
    if (!targetGroup) {
        groups.push_back(IppGroup{groupTag, {}});
        targetGroup = &groups.back();
    }

    std::vector<uint8_t> val = { static_cast<uint8_t>(value ? 1 : 0) };
    targetGroup->attributes.push_back(IppAttribute{IppTag::BOOLEAN, name, std::move(val)});
}

void IppMessage::addResolutionAttribute(IppTag groupTag, const std::string &name, int32_t xres, int32_t yres, uint8_t units) {
    IppGroup *targetGroup = nullptr;
    for (auto &g : groups) {
        if (g.groupTag == groupTag) {
            targetGroup = &g;
            break;
        }
    }
    if (!targetGroup) {
        groups.push_back(IppGroup{groupTag, {}});
        targetGroup = &groups.back();
    }

    std::vector<uint8_t> val = {
        static_cast<uint8_t>((xres >> 24) & 0xFF),
        static_cast<uint8_t>((xres >> 16) & 0xFF),
        static_cast<uint8_t>((xres >> 8) & 0xFF),
        static_cast<uint8_t>(xres & 0xFF),
        static_cast<uint8_t>((yres >> 24) & 0xFF),
        static_cast<uint8_t>((yres >> 16) & 0xFF),
        static_cast<uint8_t>((yres >> 8) & 0xFF),
        static_cast<uint8_t>(yres & 0xFF),
        units
    };
    targetGroup->attributes.push_back(IppAttribute{IppTag::RESOLUTION, name, std::move(val)});
}

static IppGroup* findOrAddGroup(std::vector<IppGroup> &groups, IppTag groupTag) {
    for (auto &g : groups) {
        if (g.groupTag == groupTag) return &g;
    }
    groups.push_back(IppGroup{groupTag, {}});
    return &groups.back();
}

void IppMessage::addRangeAttribute(IppTag groupTag, const std::string &name, int32_t lower, int32_t upper) {
    std::vector<uint8_t> val;
    for (int32_t v : {lower, upper}) {
        val.push_back(static_cast<uint8_t>((v >> 24) & 0xFF));
        val.push_back(static_cast<uint8_t>((v >> 16) & 0xFF));
        val.push_back(static_cast<uint8_t>((v >> 8) & 0xFF));
        val.push_back(static_cast<uint8_t>(v & 0xFF));
    }
    findOrAddGroup(groups, groupTag)->attributes.push_back(IppAttribute{IppTag::RANGE_OF_INTEGER, name, std::move(val)});
}

void IppMessage::addDateTimeAttribute(IppTag groupTag, const std::string &name, int64_t epochSeconds) {
    time_t t = static_cast<time_t>(epochSeconds);
    struct tm tmv;
    gmtime_r(&t, &tmv);
    int year = tmv.tm_year + 1900;
    std::vector<uint8_t> val = {
        static_cast<uint8_t>((year >> 8) & 0xFF), static_cast<uint8_t>(year & 0xFF),
        static_cast<uint8_t>(tmv.tm_mon + 1), static_cast<uint8_t>(tmv.tm_mday),
        static_cast<uint8_t>(tmv.tm_hour), static_cast<uint8_t>(tmv.tm_min),
        static_cast<uint8_t>(tmv.tm_sec), 0,
        '+', 0, 0
    };
    findOrAddGroup(groups, groupTag)->attributes.push_back(IppAttribute{IppTag::DATE_TIME, name, std::move(val)});
}

void IppMessage::beginCollection(IppTag groupTag, const std::string &name) {
    findOrAddGroup(groups, groupTag)->attributes.push_back(IppAttribute{IppTag::BEG_COLLECTION, name, {}});
}

void IppMessage::endCollection(IppTag groupTag) {
    findOrAddGroup(groups, groupTag)->attributes.push_back(IppAttribute{IppTag::END_COLLECTION, "", {}});
}

void IppMessage::addCollectionMemberName(IppTag groupTag, const std::string &member) {
    findOrAddGroup(groups, groupTag)->attributes.push_back(
        IppAttribute{IppTag::MEMBER_ATTR_NAME, "", std::vector<uint8_t>(member.begin(), member.end())});
}

const IppAttribute* IppMessage::findAttribute(const std::string &name) const {
    for (const auto &g : groups) {
        for (const auto &attr : g.attributes) {
            if (attr.name == name) {
                return &attr;
            }
        }
    }
    return nullptr;
}

std::string IppMessage::getPrinterUri() const {
    const auto *attr = findAttribute("printer-uri");
    return attr ? attr->asString() : "";
}

std::string IppMessage::getJobName() const {
    const auto *attr = findAttribute("job-name");
    return attr ? attr->asString() : "Untitled Print Job";
}

std::string IppMessage::getRequestingUserName() const {
    const auto *attr = findAttribute("requesting-user-name");
    return attr ? attr->asString() : "anonymous";
}

} // namespace cuppa
