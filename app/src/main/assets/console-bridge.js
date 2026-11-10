/* Same-origin device console transport. All network I/O goes through the pinned native client. */
(function () {
  'use strict';
  if (window.__fnBridgeInstalled) return;
  window.__fnBridgeInstalled = true;
  var native = window.FnDevice;
  function bytes64(bytes) { var s = ''; for (var i = 0; i < bytes.length; i += 8192) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192)); return btoa(s); }
  function utf8(s) { return new TextEncoder().encode(s); }
  function from64(s) { var x = atob(s), b = new Uint8Array(x.length); for (var i = 0; i < x.length; i++) b[i] = x.charCodeAt(i); return b; }
  function target(url) { var u = new URL(url, document.baseURI); if (u.origin !== location.origin || u.username || u.password) throw new Error('Blocked external console request'); return u.pathname + u.search; }
  function exchange(method, url, bytes, type) {
    var result = JSON.parse(native.exchange(JSON.stringify({method: method, path: target(url), body: bytes ? bytes64(bytes) : '', contentType: type || ''})));
    result.bytes = from64(result.body || ''); return result;
  }
  function encode(body, contentType, done, fail) {
    if (body == null) return done(null, contentType);
    if (typeof body === 'string') return done(utf8(body), contentType || 'application/x-www-form-urlencoded; charset=UTF-8');
    if (body instanceof URLSearchParams) return done(utf8(body.toString()), 'application/x-www-form-urlencoded; charset=UTF-8');
    if (body instanceof ArrayBuffer) return done(new Uint8Array(body), contentType);
    if (ArrayBuffer.isView(body)) return done(new Uint8Array(body.buffer, body.byteOffset, body.byteLength), contentType);
    if (body instanceof FormData) {
      var boundary = 'FnSwitch' + Date.now() + Math.random().toString(16).slice(2), parts = [];
      body.forEach(function (value, key) {
        var head = '--' + boundary + '\r\nContent-Disposition: form-data; name="' + key.replace(/["\r\n]/g, '_') + '"';
        if (value instanceof Blob) { head += '; filename="' + (value.name || 'upload').replace(/["\r\n]/g, '_') + '"\r\nContent-Type: ' + (value.type || 'application/octet-stream'); }
        parts.push(head + '\r\n\r\n', value, '\r\n');
      });
      parts.push('--' + boundary + '--\r\n');
      body = new Blob(parts); contentType = 'multipart/form-data; boundary=' + boundary;
    }
    if (body instanceof Blob) { var reader = new FileReader(); reader.onload = function () { done(new Uint8Array(reader.result), contentType || body.type); }; reader.onerror = fail; reader.readAsArrayBuffer(body); return; }
    fail(new Error('Unsupported request body'));
  }
  function XHR() { this.readyState = 0; this.status = 0; this.responseType = ''; this.responseText = ''; this.response = null; this._headers = {}; this._events = {}; this.upload = {addEventListener: function () {}, removeEventListener: function () {}}; }
  XHR.UNSENT = 0; XHR.OPENED = 1; XHR.HEADERS_RECEIVED = 2; XHR.LOADING = 3; XHR.DONE = 4;
  XHR.prototype.addEventListener = function (name, fn) { (this._events[name] || (this._events[name] = [])).push(fn); };
  XHR.prototype.removeEventListener = function (name, fn) { this._events[name] = (this._events[name] || []).filter(function (f) { return f !== fn; }); };
  XHR.prototype._fire = function (name) { var event = {type: name, target: this}; if (typeof this['on' + name] === 'function') this['on' + name](event); (this._events[name] || []).forEach(function (fn) { fn(event); }); };
  XHR.prototype.open = function (method, url, async) { this._method = method.toUpperCase(); this._url = target(url); this._async = async !== false; this._aborted = false; this.readyState = 1; this._fire('readystatechange'); };
  XHR.prototype.setRequestHeader = function (key, value) { this._headers[key.toLowerCase()] = value; };
  XHR.prototype.overrideMimeType = function (value) { this._mime = value; };
  XHR.prototype.getAllResponseHeaders = function () { var h = this._resultHeaders || {}; return Object.keys(h).map(function (k) { return k + ': ' + h[k]; }).join('\r\n'); };
  XHR.prototype.getResponseHeader = function (name) { var h = this._resultHeaders || {}, key = Object.keys(h).filter(function (k) { return k.toLowerCase() === name.toLowerCase(); })[0]; return key ? h[key] : null; };
  XHR.prototype.abort = function () { this._aborted = true; this.status = 0; this.readyState = 0; this._fire('abort'); this._fire('loadend'); };
  XHR.prototype.send = function (body) {
    var self = this;
    function fail() { if (self._aborted) return; self.status = 0; self.readyState = 4; self._fire('readystatechange'); self._fire('error'); self._fire('loadend'); }
    function run(bytes, type) {
      if (self._aborted) return;
      try {
        var r = exchange(self._method, self._url, bytes, type);
        if (self._aborted) return;
        self.status = r.status; self.statusText = r.reason || ''; self.responseURL = new URL(self._url, location.origin).href; self._resultHeaders = r.headers || {};
        self.responseText = new TextDecoder('utf-8').decode(r.bytes);
        if (self.responseType === 'arraybuffer') self.response = r.bytes.buffer;
        else if (self.responseType === 'blob') self.response = new Blob([r.bytes], {type: self.getResponseHeader('Content-Type') || ''});
        else if (self.responseType === 'json') { try { self.response = JSON.parse(self.responseText); } catch (_) { self.response = null; } }
        else self.response = self.responseText;
        self.readyState = 4; self._fire('readystatechange'); self._fire('load'); self._fire('loadend');
      } catch (_) { fail(); }
    }
    encode(body, this._headers['content-type'], function (bytes, type) { if (self._async) setTimeout(function () { run(bytes, type); }, 0); else run(bytes, type); }, fail);
  };
  window.XMLHttpRequest = XHR;
  window.fetch = function (url, init) { init = init || {}; return new Promise(function (resolve, reject) { encode(init.body, init.headers && (init.headers['Content-Type'] || init.headers['content-type']), function (bytes, type) { try { var r = exchange((init.method || 'GET').toUpperCase(), typeof url === 'string' ? url : url.url, bytes, type); resolve(new Response(r.bytes, {status: r.status, statusText: r.reason, headers: r.headers})); } catch (e) { reject(e); } }, reject); }); };
  function submit(form) {
    var action = target(form.action || location.href), method = (form.method || 'GET').toUpperCase(), data = new FormData(form);
    if (method === 'GET') { var q = new URLSearchParams(); data.forEach(function (v, k) { if (typeof v === 'string') q.append(k, v); }); location.href = action + (action.indexOf('?') < 0 ? '?' : '&') + q; return; }
    encode(data, '', function (bytes, type) {
      try {
        var r = exchange('POST', action, bytes, type);
        if (r.status < 200 || r.status >= 400) throw new Error('Upload failed: ' + r.status);
        var frame = form.target && form.target !== '_self' ? document.querySelector('iframe[name="' + form.target.replace(/["\\]/g, '') + '"]') : null;
        var doc = frame ? frame.contentDocument : document;
        var base = '<base href="' + new URL(action, location.origin).href.replace(/"/g, '&quot;') + '">';
        var html = new TextDecoder('utf-8').decode(r.bytes);
        doc.open(); doc.write(base + '<script src="/__fn/bridge.js"><\/script>' + html); doc.close();
      } catch (e) { alert(e.message); }
    }, function () { alert('Upload could not be completed.'); });
  }
  HTMLFormElement.prototype.submit = function () { submit(this); };
  document.addEventListener('submit', function (event) { event.preventDefault(); submit(event.target); }, true);
})();
