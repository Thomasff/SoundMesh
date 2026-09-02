const ESCAPES = Object.freeze([['&', '&amp;'], ['<', '&lt;'], ['>', '&gt;'], ['"', '&quot;'], ["'", '&apos;']]);
const UNESCAPES = Object.freeze([['&lt;', '<'], ['&gt;', '>'], ['&quot;', '"'], ['&apos;', "'"], ['&amp;', '&']]);

export const SERVICES = Object.freeze(['AVTransport', 'RenderingControl', 'ConnectionManager']);

export function escapeXml(text) {
  let escaped = String(text);
  for (const [from, to] of ESCAPES) escaped = escaped.split(from).join(to);
  return escaped;
}

export function unescapeXml(text) {
  let plain = String(text);
  for (const [from, to] of UNESCAPES) plain = plain.split(from).join(to);
  return plain;
}

export function deviceDescription({ uuid, baseUrl }) {
  const serviceXml = SERVICES.map(service => `      <service>
        <serviceType>urn:schemas-upnp-org:service:${service}:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:${service}</serviceId>
        <SCPDURL>/scpd/${service}.xml</SCPDURL>
        <controlURL>/control/${service}</controlURL>
        <eventSubURL>/event/${service}</eventSubURL>
      </service>`).join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>${escapeXml(baseUrl)}</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>SoundMesh Probe Renderer</friendlyName>
    <manufacturer>SoundMesh</manufacturer>
    <modelName>SoundMesh Probe Renderer</modelName>
    <modelNumber>0.1.0</modelNumber>
    <UDN>uuid:${escapeXml(uuid)}</UDN>
    <serviceList>
${serviceXml}
    </serviceList>
  </device>
</root>
`;
}

/** Extracts the action name from a SOAPACTION header, or null when it is not one of ours. */
export function parseSoapAction(header) {
  const match = String(header ?? '').match(/urn:schemas-upnp-org:service:[A-Za-z]+:1#([A-Za-z]+)/);
  return match ? match[1] : null;
}

export function readSoapArgument(body, name) {
  const safeName = String(name).replace(/[^A-Za-z0-9_]/g, '');
  const match = String(body ?? '').match(new RegExp(`<${safeName}[^>]*>([^<]*)</${safeName}>`));
  return match ? unescapeXml(match[1]) : null;
}

export function soapResponse(service, action, values = {}) {
  const body = Object.entries(values)
    .map(([key, value]) => `      <${key}>${escapeXml(value)}</${key}>`)
    .join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <u:${action}Response xmlns:u="urn:schemas-upnp-org:service:${service}:1">
${body}
    </u:${action}Response>
  </s:Body>
</s:Envelope>
`;
}

export function soapFault(errorCode, description) {
  return `<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
  <s:Body>
    <s:Fault>
      <faultcode>s:Client</faultcode>
      <faultstring>UPnPError</faultstring>
      <detail>
        <UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
          <errorCode>${errorCode}</errorCode>
          <errorDescription>${escapeXml(description)}</errorDescription>
        </UPnPError>
      </detail>
    </s:Fault>
  </s:Body>
</s:Envelope>
`;
}
