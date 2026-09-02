import assert from 'node:assert/strict';
import test from 'node:test';
import { deviceDescription, escapeXml, parseSoapAction, readSoapArgument, soapFault, soapResponse } from '../src/dlna/xml.mjs';

const UUID = '12345678-1234-1234-1234-1234567890ab';

test('advertises a MediaRenderer with a stable uuid and all three control urls', () => {
  const xml = deviceDescription({ uuid: UUID, baseUrl: 'http://192.168.1.5:8200' });
  assert.match(xml, /urn:schemas-upnp-org:device:MediaRenderer:1/);
  assert.match(xml, /<friendlyName>SoundMesh Probe Renderer<\/friendlyName>/);
  assert.match(xml, new RegExp(`<UDN>uuid:${UUID}</UDN>`));
  for (const service of ['AVTransport', 'RenderingControl', 'ConnectionManager']) {
    assert.match(xml, new RegExp(`urn:schemas-upnp-org:service:${service}:1`));
    assert.match(xml, new RegExp(`<controlURL>/control/${service}</controlURL>`));
  }
});

test('escapes markup and quotes in metadata without mangling non-ascii', () => {
  assert.equal(escapeXml('a & b < c > d " e \' f'), 'a &amp; b &lt; c &gt; d &quot; e &apos; f');
  assert.equal(escapeXml('张三 & 李四'), '张三 &amp; 李四');
});

test('reads the action name and arguments a control point sends', () => {
  assert.equal(parseSoapAction('"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI"'), 'SetAVTransportURI');
  assert.equal(parseSoapAction('nonsense'), null);
  const body = '<s:Envelope><s:Body><u:SetAVTransportURI><CurrentURI>http://h/a.mp3?x=1&amp;y=2</CurrentURI></u:SetAVTransportURI></s:Body></s:Envelope>';
  assert.equal(readSoapArgument(body, 'CurrentURI'), 'http://h/a.mp3?x=1&y=2');
  assert.equal(readSoapArgument(body, 'Missing'), null);
});

test('renders a soap response and a soap fault with the upnp error code', () => {
  const ok = soapResponse('AVTransport', 'GetVolume', { CurrentVolume: 50 });
  assert.match(ok, /<u:GetVolumeResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">/);
  assert.match(ok, /<CurrentVolume>50<\/CurrentVolume>/);

  const fault = soapFault(401, 'Invalid Action');
  assert.match(fault, /<errorCode>401<\/errorCode>/);
  assert.match(fault, /<errorDescription>Invalid Action<\/errorDescription>/);
  assert.match(fault, /<faultcode>s:Client<\/faultcode>/);
});
