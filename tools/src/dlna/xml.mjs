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
  // X_DLNADOC marks this as a DLNA Digital Media Renderer; some control points require it.
  return `<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <URLBase>${escapeXml(baseUrl)}</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>SoundMesh Probe Renderer</friendlyName>
    <manufacturer>SoundMesh</manufacturer>
    <manufacturerURL>http://localhost/</manufacturerURL>
    <modelDescription>SoundMesh audio source feasibility probe</modelDescription>
    <modelName>SoundMesh Probe Renderer</modelName>
    <modelNumber>0.1.0</modelNumber>
    <modelURL>http://localhost/</modelURL>
    <serialNumber>${escapeXml(uuid)}</serialNumber>
    <UDN>uuid:${escapeXml(uuid)}</UDN>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <presentationURL>/</presentationURL>
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

const STATE_VARIABLES = Object.freeze({
  AVTransport: [
    ['A_ARG_TYPE_InstanceID', 'ui4'], ['CurrentURI', 'string'], ['CurrentURIMetaData', 'string'],
    ['TransportState', 'string'], ['TransportStatus', 'string'], ['TransportPlaySpeed', 'string'],
    ['A_ARG_TYPE_SeekMode', 'string'], ['A_ARG_TYPE_SeekTarget', 'string'], ['AbsoluteTimePosition', 'string'],
    ['RelativeTimePosition', 'string'], ['CurrentTrack', 'ui4'], ['CurrentTrackDuration', 'string'],
    ['CurrentTrackURI', 'string'], ['NumberOfTracks', 'ui4'], ['CurrentMediaDuration', 'string'], ['PlaybackStorageMedium', 'string']
  ],
  RenderingControl: [
    ['A_ARG_TYPE_InstanceID', 'ui4'], ['A_ARG_TYPE_Channel', 'string'], ['Volume', 'ui2'], ['Mute', 'boolean']
  ],
  ConnectionManager: [
    ['SourceProtocolInfo', 'string'], ['SinkProtocolInfo', 'string'], ['CurrentConnectionIDs', 'string'],
    ['A_ARG_TYPE_ConnectionID', 'i4'], ['A_ARG_TYPE_RcsID', 'i4'], ['A_ARG_TYPE_AVTransportID', 'i4'],
    ['A_ARG_TYPE_ProtocolInfo', 'string'], ['A_ARG_TYPE_ConnectionManager', 'string'],
    ['A_ARG_TYPE_Direction', 'string'], ['A_ARG_TYPE_ConnectionStatus', 'string']
  ]
});

const ACTIONS = Object.freeze({
  AVTransport: {
    SetAVTransportURI: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['CurrentURI', 'in', 'CurrentURI'], ['CurrentURIMetaData', 'in', 'CurrentURIMetaData']],
    Play: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['Speed', 'in', 'TransportPlaySpeed']],
    Pause: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID']],
    Stop: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID']],
    Seek: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['Unit', 'in', 'A_ARG_TYPE_SeekMode'], ['Target', 'in', 'A_ARG_TYPE_SeekTarget']],
    GetTransportInfo: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['CurrentTransportState', 'out', 'TransportState'], ['CurrentTransportStatus', 'out', 'TransportStatus'], ['CurrentSpeed', 'out', 'TransportPlaySpeed']],
    GetPositionInfo: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['Track', 'out', 'CurrentTrack'], ['TrackDuration', 'out', 'CurrentTrackDuration'], ['RelTime', 'out', 'RelativeTimePosition'], ['AbsTime', 'out', 'AbsoluteTimePosition'], ['TrackURI', 'out', 'CurrentTrackURI']],
    GetMediaInfo: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['NrTracks', 'out', 'NumberOfTracks'], ['MediaDuration', 'out', 'CurrentMediaDuration'], ['CurrentURI', 'out', 'CurrentURI'], ['PlayMedium', 'out', 'PlaybackStorageMedium']]
  },
  RenderingControl: {
    GetVolume: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['Channel', 'in', 'A_ARG_TYPE_Channel'], ['CurrentVolume', 'out', 'Volume']],
    SetVolume: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['Channel', 'in', 'A_ARG_TYPE_Channel'], ['DesiredVolume', 'in', 'Volume']],
    GetMute: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['Channel', 'in', 'A_ARG_TYPE_Channel'], ['CurrentMute', 'out', 'Mute']],
    SetMute: [['InstanceID', 'in', 'A_ARG_TYPE_InstanceID'], ['Channel', 'in', 'A_ARG_TYPE_Channel'], ['DesiredMute', 'in', 'Mute']]
  },
  ConnectionManager: {
    GetProtocolInfo: [['Source', 'out', 'SourceProtocolInfo'], ['Sink', 'out', 'SinkProtocolInfo']],
    GetCurrentConnectionIDs: [['ConnectionIDs', 'out', 'CurrentConnectionIDs']],
    GetCurrentConnectionInfo: [['ConnectionID', 'in', 'A_ARG_TYPE_ConnectionID'], ['RcsID', 'out', 'A_ARG_TYPE_RcsID'], ['AVTransportID', 'out', 'A_ARG_TYPE_AVTransportID'], ['ProtocolInfo', 'out', 'A_ARG_TYPE_ProtocolInfo'], ['PeerConnectionManager', 'out', 'A_ARG_TYPE_ConnectionManager'], ['PeerConnectionID', 'out', 'A_ARG_TYPE_ConnectionID'], ['Direction', 'out', 'A_ARG_TYPE_Direction'], ['Status', 'out', 'A_ARG_TYPE_ConnectionStatus']]
  }
});

/** A control point that validates the action list must see the actions we really implement. */
export function serviceDescription(service) {
  const actions = ACTIONS[service];
  const variables = STATE_VARIABLES[service];
  if (!actions || !variables) return '<?xml version="1.0"?><scpd xmlns="urn:schemas-upnp-org:service-1-0"><specVersion><major>1</major><minor>0</minor></specVersion><actionList/><serviceStateTable/></scpd>';
  const actionXml = Object.entries(actions).map(([name, args]) => `    <action>
      <name>${name}</name>
      <argumentList>
${args.map(([argName, direction, related]) => `        <argument><name>${argName}</name><direction>${direction}</direction><relatedStateVariable>${related}</relatedStateVariable></argument>`).join('\n')}
      </argumentList>
    </action>`).join('\n');
  const variableXml = variables.map(([name, type]) => `    <stateVariable sendEvents="${name.startsWith('A_ARG_TYPE_') ? 'no' : 'yes'}"><name>${name}</name><dataType>${type}</dataType></stateVariable>`).join('\n');
  return `<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
${actionXml}
  </actionList>
  <serviceStateTable>
${variableXml}
  </serviceStateTable>
</scpd>
`;
}
