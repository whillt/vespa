// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "configvalue.h"
#include "payload_converter.h"
#include "misc.h"
#include <vespa/vespalib/data/slime/slime.h>

namespace config {

ConfigValue::ConfigValue(const std::vector<vespalib::string> & lines, const vespalib::string & md5sum)
    : _payload(),
      _lines(lines),
      _md5sum(md5sum)
{ }

ConfigValue::ConfigValue()
    : _payload(),
      _lines(),
      _md5sum()
{ }

ConfigValue::ConfigValue(PayloadPtr payload, const vespalib::string & md5)
    : _payload(std::move(payload)),
      _lines(),
      _md5sum(md5)
{ }

ConfigValue::ConfigValue(const ConfigValue &) = default;
ConfigValue & ConfigValue::operator = (const ConfigValue &) = default;

ConfigValue::~ConfigValue() = default;

int
ConfigValue::operator==(const ConfigValue & rhs) const
{
    return (_md5sum.compare(rhs._md5sum) == 0);
}

int
ConfigValue::operator!=(const ConfigValue & rhs) const
{
    return (!(*this == rhs));
}

std::vector<vespalib::string>
ConfigValue::getLegacyFormat() const
{
    std::vector<vespalib::string> lines;
    if (_payload) {
        const vespalib::slime::Inspector & payload(_payload->getSlimePayload());
        PayloadConverter converter(payload);
        lines = converter.convert();
    } else {
        lines = _lines;
    }
    return lines;
}

const vespalib::string
ConfigValue::asJson() const {
    const vespalib::slime::Inspector & payload(_payload->getSlimePayload());
    return payload.toString();
}

void
ConfigValue::serializeV1(vespalib::slime::Cursor & cursor) const
{
    // TODO: Remove v1 when we can bump disk format.
    std::vector<vespalib::string> lines(getLegacyFormat());
    for (size_t i = 0; i < lines.size(); i++) {
        cursor.addString(vespalib::Memory(lines[i]));
    }
}

void
ConfigValue::serializeV2(vespalib::slime::Cursor & cursor) const
{
    copySlimeObject(_payload->getSlimePayload(), cursor);
}

}

