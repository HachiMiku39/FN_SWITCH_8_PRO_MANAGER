import fs from 'node:fs/promises';
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {Blob,Buffer} from 'node:buffer';
import {URL,URLSearchParams} from 'node:url';
import {TextEncoder,TextDecoder} from 'node:util';
import {setTimeout,clearTimeout} from 'node:timers';
class Response { constructor(bytes,options) { this.bytes=bytes; this.status=options.status; this.headers=options.headers; } async arrayBuffer() { return this.bytes.buffer.slice(this.bytes.byteOffset,this.bytes.byteOffset+this.bytes.byteLength); } }
export async function run() {
  let assertions = 0;
  const check = new Proxy(assert, {get(object,key) { const value=object[key]; return typeof value==='function' ? (...args)=>{assertions++;return value(...args);} : value; }});
  const code = await fs.readFile(new URL('../app/src/main/assets/console-bridge.js', import.meta.url), 'utf8');
  const requests = [], events = {}, writes = [];
  let reply = {status: 200, reason: 'OK', headers: {'Content-Type': 'application/json'}, body: Buffer.from('{"name":"交换机"}').toString('base64')};
  class Data { constructor(form) { this.entries = form?.entries ? [...form.entries] : []; } append(k,v) {this.entries.push([k,v]);} forEach(fn) {this.entries.forEach(([k,v]) => fn(v,k));} }
  class Reader { readAsArrayBuffer(blob) {blob.arrayBuffer().then(bytes => {this.result = bytes; this.onload();}, e => {this.onerror(e);});} }
  class Form { constructor(entries=[]) {this.entries=entries;this.action='https://switch.invalid/cgi/upload.cgi';this.method='POST';this.target='';} }
  const context = {URL,URLSearchParams,TextEncoder,TextDecoder,Uint8Array,ArrayBuffer,Blob,Response,Promise,FormData:Data,FileReader:Reader,HTMLFormElement:Form,
    setTimeout,clearTimeout,btoa:s=>Buffer.from(s,'binary').toString('base64'),atob:s=>Buffer.from(s,'base64').toString('binary'),
    location:{origin:'https://switch.invalid',href:'https://switch.invalid/home.html'},
    document:{baseURI:'https://switch.invalid/html/page.html',addEventListener:(name,fn)=>{events[name]=fn;},querySelector:()=>null,open(){},write(s){writes.push(s);},close(){}},
    alert:s=>{throw Error(s);},FnDevice:{exchange:s=>{requests.push(JSON.parse(s));return JSON.stringify(reply);}}};
  context.window=context;vm.runInNewContext(code,context);
  const xhr=new context.XMLHttpRequest();xhr.open('GET','../cgi/get.cgi?cmd=sys_sysinfo',false);xhr.send();
  check.equal(requests.at(-1).path,'/cgi/get.cgi?cmd=sys_sysinfo');check.equal(JSON.parse(xhr.responseText).name,'交换机');check.equal(xhr.status,200);
  const post=new context.XMLHttpRequest();post.open('POST','/cgi/set.cgi?cmd=test',false);post.setRequestHeader('Content-Type','application/x-www-form-urlencoded');post.send('name=交换机&_csrf=abc');
  check.equal(Buffer.from(requests.at(-1).body,'base64').toString(),'name=交换机&_csrf=abc');check.equal(requests.at(-1).contentType,'application/x-www-form-urlencoded');
  let loaded=false;const async=new context.XMLHttpRequest();async.open('GET','/cgi/get.cgi?cmd=test');async.onload=()=>loaded=true;async.send();await new Promise(r=>setTimeout(r,20));check.equal(loaded,true);
  const before=requests.length;const aborted=new context.XMLHttpRequest();aborted.open('GET','/cgi/get.cgi?cmd=test');aborted.send();aborted.abort();await new Promise(r=>setTimeout(r,20));check.equal(requests.length,before);
  for (const url of ['https://example.com/','http://switch.invalid/','//evil.invalid/a','file:///etc/passwd','https://u@switch.invalid/']) check.throws(()=>{const bad=new context.XMLHttpRequest();bad.open('GET',url);});
  const form=new Data();form.append('description','交换机');const blob=new Blob([new Uint8Array([1,2,0,255])],{type:'application/octet-stream'});Object.defineProperty(blob,'name',{value:'firmware.fmw'});form.append('fileName',blob);
  const upload=new context.XMLHttpRequest();upload.open('POST','/cgi-bin/httpupload.cgi?_csrf=abc');upload.send(form);await new Promise(r=>setTimeout(r,30));
  const sent=requests.at(-1), multipart=Buffer.from(sent.body,'base64');check.ok(sent.contentType.startsWith('multipart/form-data; boundary=FnSwitch'));check.ok(multipart.includes(Buffer.from('交换机')));check.ok(multipart.includes(Buffer.from([1,2,0,255])));check.ok(multipart.toString().includes('filename="firmware.fmw"'));
  reply={status:200,reason:'OK',headers:{'Content-Type':'application/octet-stream'},body:Buffer.from([0,1,255]).toString('base64')};
  const fetched=await context.fetch('/backup.bin');check.deepEqual([...new Uint8Array(await fetched.arrayBuffer())],[0,1,255]);
  reply={status:200,reason:'OK',headers:{'Content-Type':'text/html'},body:Buffer.from('<html><body>Accepted</body></html>').toString('base64')};
  new Form([['fileType','img']]).submit();await new Promise(r=>setTimeout(r,30));check.ok(writes.at(-1).includes('/__fn/bridge.js'));check.ok(writes.at(-1).includes('Accepted'));check.ok(writes.at(-1).includes('https://switch.invalid/cgi/upload.cgi'));
  const count=requests.length;vm.runInNewContext(code,context);check.equal(requests.length,count);
  return {assertions, requests:requests.length, checks:['synchronous and asynchronous XHR','UTF-8 fields','abort before send','blocked external/file/HTTP origins','binary multipart upload','fetch binary response','HTML form submission','bridge initialization once']};
}
