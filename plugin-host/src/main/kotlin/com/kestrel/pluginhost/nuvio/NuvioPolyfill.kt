// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

/**
 * Browser-like JavaScript host polyfill injected into QuickJS before running Nuvio, Vega, Zangetsu, and SkyStream scrapers:
 * - fetch(url, options) with followRedirects, timeoutMs, browser:true WebView fallback, and case-insensitive headers
 * - Headers, AbortController, axios shim, URL, URLSearchParams, absUrl
 * - Stateful Jsoup-backed cheerio.load(html) supporting $(sel, ctx), nextUntil, remove, empty, clone, add, Symbol.iterator, etc.
 * - Native-backed CryptoJS (AES CBC/ECB/CTR + OpenSSL EVP_BytesToKey, MD5, SHA1/256/512, HMAC, enc.Utf8/Base64/Hex/Latin1)
 * - TextEncoder, TextDecoder, Buffer, atob/btoa, console, setTimeout/clearTimeout
 * - __nuvioResult(payload) bridge back to Kotlin
 */
internal object NuvioPolyfill {
    fun build(): String = """
        (function(global) {
          if (global.__kestrelPolyfillLoaded) return;
          global.__kestrelPolyfillLoaded = true;
          global.global = global;
          global.window = global;
          global.self = global;

          global.console = {
            log: function() { __nuvioCall(JSON.stringify({ op: 'log', message: Array.prototype.slice.call(arguments).join(' ') })); },
            warn: function() { __nuvioCall(JSON.stringify({ op: 'log', message: '[WARN] ' + Array.prototype.slice.call(arguments).join(' ') })); },
            error: function() { __nuvioCall(JSON.stringify({ op: 'log', message: '[ERR] ' + Array.prototype.slice.call(arguments).join(' ') })); },
            info: function() { __nuvioCall(JSON.stringify({ op: 'log', message: '[INFO] ' + Array.prototype.slice.call(arguments).join(' ') })); },
            debug: function() {}
          };

          var __nextTimerId = 1;
          var __activeTimers = {};
          global.setTimeout = function(fn, ms) {
            var id = __nextTimerId++;
            __activeTimers[id] = true;
            var args = Array.prototype.slice.call(arguments, 2);
            Promise.resolve().then(function() {
              return __nuvioSleepAsync(ms || 0);
            }).then(function() {
              if (!__activeTimers[id]) return;
              delete __activeTimers[id];
              if (typeof fn === 'function') fn.apply(null, args);
            });
            return id;
          };
          global.clearTimeout = function(id) { delete __activeTimers[id]; };
          global.setInterval = function() { return 1; };
          global.clearInterval = function() {};

          global.btoa = function(str) {
            var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'btoa', value: String(str) })) || '{}');
            return res.value || '';
          };
          global.atob = function(str) {
            var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'atob', value: String(str) })) || '{}');
            return res.value || '';
          };

          function bytesToHex(bytes) {
            var out = '';
            for (var i = 0; i < bytes.length; i++) {
              var h = (bytes[i] & 0xff).toString(16);
              if (h.length < 2) out += '0';
              out += h;
            }
            return out;
          }

          function hexToBytes(hex) {
            var clean = String(hex || '').replace(/[^0-9a-fA-F]/g, '');
            var out = [];
            for (var i = 0; i + 1 < clean.length; i += 2) {
              out.push(parseInt(clean.slice(i, i + 2), 16));
            }
            return out;
          }

          function utf8StrToBytes(str) {
            var utf8 = unescape(encodeURIComponent(String(str || '')));
            var out = [];
            for (var i = 0; i < utf8.length; i++) {
              out.push(utf8.charCodeAt(i) & 0xff);
            }
            return out;
          }

          function bytesToUtf8Str(bytes) {
            var s = '';
            for (var i = 0; i < bytes.length; i++) {
              s += String.fromCharCode(bytes[i] & 0xff);
            }
            try {
              return decodeURIComponent(escape(s));
            } catch (e) {
              return s;
            }
          }

          function latin1StrToBytes(str) {
            var s = String(str || '');
            var out = [];
            for (var i = 0; i < s.length; i++) {
              out.push(s.charCodeAt(i) & 0xff);
            }
            return out;
          }

          function bytesToLatin1Str(bytes) {
            var out = '';
            for (var i = 0; i < bytes.length; i++) {
              out += String.fromCharCode(bytes[i] & 0xff);
            }
            return out;
          }

          if (typeof global.TextEncoder === 'undefined') {
            global.TextEncoder = function() {};
            global.TextEncoder.prototype.encode = function(str) {
              return new Uint8Array(utf8StrToBytes(str));
            };
          }

          if (typeof global.TextDecoder === 'undefined') {
            global.TextDecoder = function() {};
            global.TextDecoder.prototype.decode = function(arr) {
              if (!arr) return '';
              return bytesToUtf8Str(arr);
            };
          }

          if (typeof global.Buffer === 'undefined') {
            function makeBuffer(byteArr) {
              var buf = new Uint8Array(byteArr);
              buf.toString = function(enc) {
                var e = String(enc || 'utf8').toLowerCase();
                if (e === 'hex') return bytesToHex(buf);
                if (e === 'base64' || e === 'base64url') {
                  var raw = global.btoa(bytesToLatin1Str(buf));
                  return e === 'base64url' ? raw.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '') : raw;
                }
                if (e === 'latin1' || e === 'binary' || e === 'ascii') return bytesToLatin1Str(buf);
                return bytesToUtf8Str(buf);
              };
              buf.slice = function(start, end) {
                return makeBuffer(Uint8Array.prototype.slice.call(buf, start, end));
              };
              return buf;
            }
            global.Buffer = {
              isBuffer: function(b) { return b instanceof Uint8Array; },
              from: function(input, encoding) {
                if (input && typeof input !== 'string' && typeof input.length === 'number') {
                  return makeBuffer(input);
                }
                var enc = String(encoding || 'utf8').toLowerCase();
                var text = String(input || '');
                if (enc === 'base64' || enc === 'base64url') {
                  var b64 = text.replace(/-/g, '+').replace(/_/g, '/');
                  return makeBuffer(latin1StrToBytes(global.atob(b64)));
                }
                if (enc === 'hex') {
                  return makeBuffer(hexToBytes(text));
                }
                if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') {
                  return makeBuffer(latin1StrToBytes(text));
                }
                return makeBuffer(utf8StrToBytes(text));
              },
              alloc: function(len) { return makeBuffer(new Uint8Array(len || 0)); },
              concat: function(list) {
                var total = 0;
                for (var i = 0; i < list.length; i++) total += list[i].length;
                var out = new Uint8Array(total);
                var offset = 0;
                for (var j = 0; j < list.length; j++) {
                  out.set(list[j], offset);
                  offset += list[j].length;
                }
                return makeBuffer(out);
              }
            };
          }

          if (typeof global.AbortController === 'undefined') {
            global.AbortController = function() {
              this.signal = {
                aborted: false,
                addEventListener: function() {},
                removeEventListener: function() {},
                dispatchEvent: function() { return true; }
              };
              this.abort = function() {
                this.signal.aborted = true;
              };
            };
          }

          if (typeof global.Headers === 'undefined') {
            global.Headers = function(init) {
              this._map = {};
              var selfHeaders = this;
              if (init && typeof init.forEach === 'function') {
                init.forEach(function(v, k) { selfHeaders.set(k, v); });
              } else if (init && typeof init === 'object') {
                Object.keys(init).forEach(function(k) { selfHeaders.set(k, init[k]); });
              }
            };
            global.Headers.prototype.set = function(k, v) { this._map[String(k).toLowerCase()] = String(v); };
            global.Headers.prototype.get = function(k) {
              var key = String(k).toLowerCase();
              return Object.prototype.hasOwnProperty.call(this._map, key) ? this._map[key] : null;
            };
            global.Headers.prototype.has = function(k) { return this.get(k) !== null; };
            global.Headers.prototype.delete = function(k) { delete this._map[String(k).toLowerCase()]; };
            global.Headers.prototype.forEach = function(cb) {
              var m = this._map;
              Object.keys(m).forEach(function(k) { cb(m[k], k); });
            };
          }

          if (typeof global.URLSearchParams === 'undefined') {
            global.URLSearchParams = function(init) {
              this._entries = [];
              var selfParams = this;
              if (typeof init === 'string') {
                var s = init.charAt(0) === '?' ? init.slice(1) : init;
                if (s) {
                  s.split('&').forEach(function(pair) {
                    if (!pair) return;
                    var idx = pair.indexOf('=');
                    var k = idx >= 0 ? decodeURIComponent(pair.slice(0, idx).replace(/\+/g, ' ')) : decodeURIComponent(pair.replace(/\+/g, ' '));
                    var v = idx >= 0 ? decodeURIComponent(pair.slice(idx + 1).replace(/\+/g, ' ')) : '';
                    selfParams._entries.push([k, v]);
                  });
                }
              } else if (init && typeof init === 'object') {
                Object.keys(init).forEach(function(k) {
                  selfParams._entries.push([String(k), String(init[k])]);
                });
              }
            };
            global.URLSearchParams.prototype.append = function(k, v) { this._entries.push([String(k), String(v)]); };
            global.URLSearchParams.prototype.set = function(k, v) {
              var key = String(k);
              this._entries = this._entries.filter(function(e) { return e[0] !== key; });
              this._entries.push([key, String(v)]);
            };
            global.URLSearchParams.prototype.get = function(k) {
              var key = String(k);
              for (var i = 0; i < this._entries.length; i++) {
                if (this._entries[i][0] === key) return this._entries[i][1];
              }
              return null;
            };
            global.URLSearchParams.prototype.getAll = function(k) {
              var key = String(k);
              return this._entries.filter(function(e) { return e[0] === key; }).map(function(e) { return e[1]; });
            };
            global.URLSearchParams.prototype.has = function(k) { return this.get(k) !== null; };
            global.URLSearchParams.prototype.delete = function(k) {
              var key = String(k);
              this._entries = this._entries.filter(function(e) { return e[0] !== key; });
            };
            global.URLSearchParams.prototype.forEach = function(cb) {
              var selfParams = this;
              this._entries.forEach(function(e) { cb(e[1], e[0], selfParams); });
            };
            global.URLSearchParams.prototype.toString = function() {
              return this._entries.map(function(e) {
                return encodeURIComponent(e[0]) + '=' + encodeURIComponent(e[1]);
              }).join('&');
            };
          }

          if (typeof global.URL === 'undefined') {
            global.URL = function(input, base) {
              var raw = String(input || '').trim();
              if (base && !/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(raw)) {
                var b = String(base).trim();
                var originMatch = b.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\/?#]+)/);
                var origin = originMatch ? originMatch[1] : '';
                if (!raw) {
                  raw = b;
                } else if (raw.indexOf('//') === 0) {
                  var protoMatch = b.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)/);
                  raw = (protoMatch ? protoMatch[1] : 'https:') + raw;
                } else if (raw.charAt(0) === '/') {
                  raw = origin + raw;
                } else if (raw.charAt(0) === '?') {
                  var baseNoQ = b.split('#')[0].split('?')[0];
                  if (origin && baseNoQ.length <= origin.length) baseNoQ = origin + '/';
                  raw = baseNoQ + raw;
                } else if (raw.charAt(0) === '#') {
                  raw = b.split('#')[0] + raw;
                } else {
                  var basePath = b.split('?')[0].split('#')[0];
                  if (origin && basePath.length <= origin.length) {
                    raw = origin + '/' + raw.replace(/^\.\//, '');
                  } else {
                    var lastSlash = basePath.lastIndexOf('/');
                    var dir = lastSlash >= origin.length ? basePath.slice(0, lastSlash + 1) : (origin + '/');
                    raw = dir + raw.replace(/^\.\//, '');
                  }
                }
              }
              var m = raw.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\/(?:[^@\/?#]+@)?([^\/?#:]+(?::\d+)?)(\/[^?#]*)?(\?[^#]*)?(#.*)?$/);
              if (!m) throw new Error('Invalid URL: ' + raw);
              this.protocol = m[1];
              this.host = m[2];
              var hostParts = m[2].split(':');
              this.hostname = hostParts[0];
              this.port = hostParts[1] || '';
              this.origin = this.protocol + '//' + this.host;
              this.pathname = m[3] || '/';
              this.search = m[4] || '';
              this.hash = m[5] || '';
              this.searchParams = new global.URLSearchParams(this.search);
              var urlObj = this;
              Object.defineProperty(this, 'href', {
                get: function() {
                  var q = urlObj.searchParams.toString();
                  return urlObj.origin + urlObj.pathname + (q ? '?' + q : '') + urlObj.hash;
                }
              });
            };
            global.URL.prototype.toString = function() { return this.href; };
            global.URL.prototype.toJSON = function() { return this.href; };
          }

          if (typeof global.absUrl === 'undefined') {
            global.absUrl = function(href, base) {
              var h = String(href == null ? '' : href).trim();
              if (!h) return '';
              if (/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(h)) return h;
              if (/^(?:data|javascript|mailto|magnet):/i.test(h)) return h;
              var b = String(base == null ? '' : base).trim();
              if (!b) return h;
              try {
                return new global.URL(h, b).toString();
              } catch (e) {
                if (h.indexOf('//') === 0) {
                  var pm = b.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)/);
                  return (pm ? pm[1] : 'https:') + h;
                }
                var om = b.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\/?#]+)/);
                var origin = om ? om[1] : b.replace(/\/+$/, '');
                if (h.charAt(0) === '/') return origin + h;
                return b.replace(/\/+$/, '') + '/' + h.replace(/^\/+/, '');
              }
            };
          }

          global.fetch = async function(url, opts) {
            if (url && typeof url === 'object' && typeof url.url === 'string') {
              opts = Object.assign({}, url, opts || {});
              url = url.url;
            }
            opts = opts || {};
            var mergedHeaders = {};
            var custom = global.__kestrelCustomHeaders || {};
            for (var ck in custom) {
              if (Object.prototype.hasOwnProperty.call(custom, ck) && custom[ck] !== undefined && custom[ck] !== null) {
                mergedHeaders[ck] = String(custom[ck]);
              }
            }
            if (opts.headers) {
              if (typeof opts.headers.forEach === 'function') {
                opts.headers.forEach(function(v, k) {
                  if (v !== undefined && v !== null) mergedHeaders[k] = String(v);
                });
              } else {
                for (var k in opts.headers) {
                  if (Object.prototype.hasOwnProperty.call(opts.headers, k) && opts.headers[k] !== undefined && opts.headers[k] !== null) {
                    mergedHeaders[k] = String(opts.headers[k]);
                  }
                }
              }
            }
            var bodyPayload = null;
            if (opts.body !== undefined && opts.body !== null) {
              bodyPayload = (opts.body instanceof global.URLSearchParams) ? opts.body.toString() : String(opts.body);
            }
            var redirectMode = opts.redirect;
            if (!redirectMode && opts.followRedirects === false) {
              redirectMode = 'manual';
            }
            var payload = {
              url: String(url),
              method: (opts.method || 'GET').toUpperCase(),
              headers: mergedHeaders,
              body: bodyPayload,
              redirect: redirectMode || 'follow',
              timeoutMs: Number(opts.timeoutMs || opts.timeout || 0)
            };
            var raw = opts.browser
              ? await __nuvioWebViewFetchAsync(JSON.stringify(payload))
              : await __nuvioFetchAsync(JSON.stringify(payload));
            var res = JSON.parse(raw);
            if (res.error) throw new Error(res.error);
            var bodyText = res.body || '';
            var rawHeaders = res.headers || {};

            function buildResponseHeaders() {
              var hdrs = {};
              for (var hk in rawHeaders) {
                if (Object.prototype.hasOwnProperty.call(rawHeaders, hk)) {
                  hdrs[hk] = rawHeaders[hk];
                  var lowerHk = String(hk).toLowerCase();
                  if (!Object.prototype.hasOwnProperty.call(hdrs, lowerHk)) {
                    hdrs[lowerHk] = rawHeaders[hk];
                  }
                }
              }
              var def = function(name, fn) {
                Object.defineProperty(hdrs, name, { value: fn, enumerable: false, configurable: true });
              };
              def('get', function(name) {
                var lower = String(name || '').toLowerCase();
                for (var h in rawHeaders) {
                  if (String(h).toLowerCase() === lower) return rawHeaders[h];
                }
                return null;
              });
              def('has', function(name) { return hdrs.get(name) !== null; });
              def('entries', function() {
                return Object.keys(rawHeaders).map(function(key) { return [key, rawHeaders[key]]; });
              });
              def('keys', function() { return Object.keys(rawHeaders); });
              def('values', function() {
                return Object.keys(rawHeaders).map(function(key) { return rawHeaders[key]; });
              });
              def('forEach', function(cb) {
                Object.keys(rawHeaders).forEach(function(key) { cb(rawHeaders[key], key, hdrs); });
              });
              return hdrs;
            }

            function makeResponse() {
              return {
                ok: res.status >= 200 && res.status < 300,
                status: res.status,
                statusText: res.statusText || '',
                url: res.url || payload.url,
                redirected: Boolean(res.redirected),
                body: bodyText,
                headers: buildResponseHeaders(),
                text: async function() { return bodyText; },
                json: async function() { return JSON.parse(bodyText); },
                arrayBuffer: async function() { return new Uint8Array(utf8StrToBytes(bodyText)).buffer; },
                bytes: async function() { return new Uint8Array(utf8StrToBytes(bodyText)); },
                clone: function() { return makeResponse(); }
              };
            }
            return makeResponse();
          };

          // --- Native-backed CryptoJS polyfill ---
          function makeWordArray(hexStr) {
            var h = String(hexStr || '').toLowerCase();
            return {
              _hex: h,
              sigBytes: Math.floor(h.length / 2),
              toString: function(encoder) {
                var enc = encoder || CryptoJSImpl.enc.Hex;
                if (enc && typeof enc.stringify === 'function') return enc.stringify(this);
                return this._hex;
              }
            };
          }

          function toHexFromInput(input) {
            if (input && typeof input === 'object' && typeof input._hex === 'string') return input._hex;
            return bytesToHex(utf8StrToBytes(String(input || '')));
          }

          var CryptoJSImpl = {
            enc: {
              Utf8: {
                parse: function(str) { return makeWordArray(bytesToHex(utf8StrToBytes(str))); },
                stringify: function(wa) { return bytesToUtf8Str(hexToBytes(wa && wa._hex ? wa._hex : '')); }
              },
              Base64: {
                parse: function(b64) { return makeWordArray(bytesToHex(latin1StrToBytes(global.atob(String(b64 || ''))))); },
                stringify: function(wa) { return global.btoa(bytesToLatin1Str(hexToBytes(wa && wa._hex ? wa._hex : ''))); }
              },
              Hex: {
                parse: function(hex) { return makeWordArray(String(hex || '')); },
                stringify: function(wa) { return wa && wa._hex ? wa._hex : ''; }
              },
              Latin1: {
                parse: function(str) { return makeWordArray(bytesToHex(latin1StrToBytes(str))); },
                stringify: function(wa) { return bytesToLatin1Str(hexToBytes(wa && wa._hex ? wa._hex : '')); }
              },
              Base64url: {
                parse: function(b64u) {
                  var b64 = String(b64u || '').replace(/-/g, '+').replace(/_/g, '/');
                  return makeWordArray(bytesToHex(latin1StrToBytes(global.atob(b64))));
                },
                stringify: function(wa) {
                  return global.btoa(bytesToLatin1Str(hexToBytes(wa && wa._hex ? wa._hex : ''))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
                }
              }
            },
            lib: {
              WordArray: {
                create: function(words, sigBytes) {
                  if (typeof words === 'string') return makeWordArray(bytesToHex(utf8StrToBytes(words)));
                  if (Array.isArray(words) || words instanceof Uint8Array) {
                    var bytes = [];
                    if (words instanceof Uint8Array) {
                      for (var i = 0; i < words.length; i++) bytes.push(words[i]);
                    } else {
                      var total = typeof sigBytes === 'number' ? sigBytes : words.length * 4;
                      for (var b = 0; b < total; b++) {
                        var w = words[b >>> 2] || 0;
                        bytes.push((w >>> (24 - (b % 4) * 8)) & 0xff);
                      }
                    }
                    return makeWordArray(bytesToHex(bytes));
                  }
                  return makeWordArray('');
                },
                random: function(nBytes) {
                  var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoRandom', size: Number(nBytes || 16) })) || '{}');
                  return makeWordArray(res.hex || '');
                }
              }
            },
            mode: { CBC: 'CBC', ECB: 'ECB', CTR: 'CTR', CFB: 'CFB', OFB: 'OFB' },
            pad: { Pkcs7: 'Pkcs7', PKCS7: 'Pkcs7', NoPadding: 'NoPadding', ZeroPadding: 'ZeroPadding' },
            MD5: function(msg) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoDigest', algo: 'MD5', hex: toHexFromInput(msg) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            SHA1: function(msg) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoDigest', algo: 'SHA-1', hex: toHexFromInput(msg) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            SHA256: function(msg) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoDigest', algo: 'SHA-256', hex: toHexFromInput(msg) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            SHA512: function(msg) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoDigest', algo: 'SHA-512', hex: toHexFromInput(msg) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            HmacMD5: function(msg, key) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoHmac', algo: 'HmacMD5', dataHex: toHexFromInput(msg), keyHex: toHexFromInput(key) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            HmacSHA1: function(msg, key) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoHmac', algo: 'HmacSHA1', dataHex: toHexFromInput(msg), keyHex: toHexFromInput(key) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            HmacSHA256: function(msg, key) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoHmac', algo: 'HmacSHA256', dataHex: toHexFromInput(msg), keyHex: toHexFromInput(key) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            HmacSHA512: function(msg, key) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoHmac', algo: 'HmacSHA512', dataHex: toHexFromInput(msg), keyHex: toHexFromInput(key) })) || '{}');
              return makeWordArray(res.hex || '');
            },
            AES: {
              encrypt: function(message, key, cfg) {
                cfg = cfg || {};
                var msgHex = toHexFromInput(message);
                var isPassphrase = typeof key === 'string';
                var req = {
                  op: 'cryptoAes',
                  action: 'encrypt',
                  inputHex: msgHex,
                  passphrase: isPassphrase ? key : null,
                  keyHex: isPassphrase ? null : toHexFromInput(key),
                  ivHex: cfg.iv ? toHexFromInput(cfg.iv) : null,
                  cipherMode: cfg.mode || 'CBC',
                  padding: cfg.padding || 'Pkcs7'
                };
                var res = JSON.parse(__nuvioCall(JSON.stringify(req)) || '{}');
                var ctWa = makeWordArray(res.hex || '');
                return {
                  ciphertext: ctWa,
                  toString: function(formatter) {
                    if (formatter === CryptoJSImpl.enc.Hex) return res.hex || '';
                    return res.base64 || '';
                  }
                };
              },
              decrypt: function(cipherParams, key, cfg) {
                cfg = cfg || {};
                var inputB64 = null;
                var inputHex = null;
                if (typeof cipherParams === 'string') {
                  inputB64 = cipherParams;
                } else if (cipherParams && cipherParams.ciphertext && cipherParams.ciphertext._hex) {
                  inputHex = cipherParams.ciphertext._hex;
                } else if (cipherParams && typeof cipherParams.toString === 'function') {
                  inputB64 = cipherParams.toString();
                }
                var isPassphrase = typeof key === 'string';
                var req = {
                  op: 'cryptoAes',
                  action: 'decrypt',
                  inputBase64: inputB64,
                  inputHex: inputHex,
                  passphrase: isPassphrase ? key : null,
                  keyHex: isPassphrase ? null : toHexFromInput(key),
                  ivHex: cfg.iv ? toHexFromInput(cfg.iv) : null,
                  cipherMode: cfg.mode || 'CBC',
                  padding: cfg.padding || 'Pkcs7'
                };
                var res = JSON.parse(__nuvioCall(JSON.stringify(req)) || '{}');
                return makeWordArray(res.hex || '');
              }
            }
          };
          global.CryptoJS = CryptoJSImpl;

          // --- Stateful Jsoup-backed Cheerio polyfill ---
          function createCheerioInstance(html) {
            var loadRes = JSON.parse(__nuvioCall(JSON.stringify({
              op: 'domLoad',
              html: String(html || '')
            })) || '{}');
            var docId = loadRes.docId || 0;
            var domVersion = 1;

            function tagNodes(nodes) {
              if (!Array.isArray(nodes)) return [];
              for (var i = 0; i < nodes.length; i++) {
                if (nodes[i] && typeof nodes[i] === 'object') {
                  nodes[i]._v = domVersion;
                }
              }
              return nodes;
            }

            function ensureFresh(node) {
              if (!node || typeof node.nodeId !== 'number') return node;
              if (node._v === domVersion) return node;
              var fresh = JSON.parse(__nuvioCall(JSON.stringify({
                op: 'domRead',
                nodeId: node.nodeId
              })) || 'null');
              if (fresh && typeof fresh === 'object') {
                node.text = fresh.text || '';
                node.html = fresh.html || '';
                node.outerHtml = fresh.outerHtml || '';
                node.attrs = fresh.attrs || node.attrs || {};
              }
              node._v = domVersion;
              return node;
            }

            function wrapNodes(nodes, prevSelection, skipIndex) {
              var arr = tagNodes(Array.isArray(nodes) ? nodes : []);
              var sel = {
                _isCheerioSelection: true,
                _nodes: arr,
                _prev: prevSelection || null,
                length: arr.length,
                attribs: arr.length ? (arr[0].attrs || {}) : {},
                tagName: arr.length ? (arr[0].tagName || '') : '',
                text: function(newVal) {
                  if (newVal !== undefined) {
                    arr.forEach(function(n) { n.text = String(newVal); });
                    return sel;
                  }
                  return arr.map(function(n) { return ensureFresh(n).text || ''; }).join('');
                },
                html: function() {
                  return arr.length ? (ensureFresh(arr[0]).html || '') : null;
                },
                attr: function(name, val) {
                  if (!arr.length) return undefined;
                  if (typeof name === 'object' && name !== null) {
                    arr[0].attrs = arr[0].attrs || {};
                    Object.keys(name).forEach(function(k) { arr[0].attrs[k] = String(name[k]); });
                    return sel;
                  }
                  if (val !== undefined) {
                    arr[0].attrs = arr[0].attrs || {};
                    arr[0].attrs[name] = String(val);
                    return sel;
                  }
                  return arr[0].attrs ? arr[0].attrs[name] : undefined;
                },
                prop: function(name) {
                  if (!arr.length) return undefined;
                  if (name === 'tagName' || name === 'nodeName') return (arr[0].tagName || '').toUpperCase();
                  return sel.attr(name);
                },
                val: function() {
                  return sel.attr('value') || sel.text() || '';
                },
                data: function(key) {
                  if (!arr.length) return undefined;
                  var attrs = arr[0].attrs || {};
                  if (key === undefined) {
                    var out = {};
                    Object.keys(attrs).forEach(function(k) {
                      if (k.indexOf('data-') === 0) out[k.slice(5)] = attrs[k];
                    });
                    return out;
                  }
                  return attrs['data-' + key];
                },
                hasClass: function(cls) {
                  var c = sel.attr('class') || '';
                  return c.split(/\s+/).indexOf(cls) >= 0;
                },
                is: function(selector) {
                  if (!arr.length || !selector) return false;
                  var res = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domIs',
                    nodeIds: arr.map(function(n) { return n.nodeId; }),
                    selector: String(selector)
                  })) || '{}');
                  return Boolean(res.match);
                },
                index: function() {
                  if (!arr.length) return -1;
                  var res = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domIndex',
                    nodeId: arr[0].nodeId
                  })) || '{}');
                  return typeof res.index === 'number' ? res.index : -1;
                },
                first: function() { return wrapNodes(arr.length ? [arr[0]] : [], sel, false); },
                last: function() { return wrapNodes(arr.length ? [arr[arr.length - 1]] : [], sel, false); },
                eq: function(i) {
                  var idx = i < 0 ? arr.length + i : i;
                  return wrapNodes(arr[idx] ? [arr[idx]] : [], sel, false);
                },
                slice: function(start, end) {
                  return wrapNodes(arr.slice(start, end), sel, false);
                },
                get: function(i) {
                  if (i === undefined) return arr.map(function(n) { return wrapNodes([n], sel, true); });
                  var idx = i < 0 ? arr.length + i : i;
                  return arr[idx] ? wrapNodes([arr[idx]], sel, true) : undefined;
                },
                find: function(sub) {
                  if (!arr.length || !sub) return wrapNodes([], sel, true);
                  var found = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domSelect',
                    docId: docId,
                    selector: String(sub),
                    contextNodeIds: arr.map(function(n) { return n.nodeId; })
                  })) || '[]');
                  return wrapNodes(found, sel, false);
                },
                add: function(other) {
                  var extra = $(other);
                  var combined = arr.slice();
                  var seen = {};
                  combined.forEach(function(n) { seen[n.nodeId] = true; });
                  (extra._nodes || []).forEach(function(n) {
                    if (!seen[n.nodeId]) {
                      seen[n.nodeId] = true;
                      combined.push(n);
                    }
                  });
                  return wrapNodes(combined, sel, false);
                },
                filter: function(matcher) {
                  if (!arr.length) return wrapNodes([], sel, true);
                  if (typeof matcher === 'function') {
                    var matched = arr.filter(function(n, idx) {
                      var w = wrapNodes([n], sel, true);
                      return Boolean(matcher.call(w, idx, w));
                    });
                    return wrapNodes(matched, sel, false);
                  }
                  var found = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domFilter',
                    nodeIds: arr.map(function(n) { return n.nodeId; }),
                    selector: String(matcher || ''),
                    invert: false
                  })) || '[]');
                  return wrapNodes(found, sel, false);
                },
                not: function(matcher) {
                  if (!arr.length) return wrapNodes([], sel, true);
                  if (typeof matcher === 'function') {
                    var kept = arr.filter(function(n, idx) {
                      var w = wrapNodes([n], sel, true);
                      return !matcher.call(w, idx, w);
                    });
                    return wrapNodes(kept, sel, false);
                  }
                  var found = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domFilter',
                    nodeIds: arr.map(function(n) { return n.nodeId; }),
                    selector: String(matcher || ''),
                    invert: true
                  })) || '[]');
                  return wrapNodes(found, sel, false);
                },
                children: function(selector) { return traverse('children', selector); },
                parent: function(selector) { return traverse('parent', selector); },
                parents: function(selector) { return traverse('parents', selector); },
                closest: function(selector) { return traverse('closest', selector); },
                next: function(selector) { return traverse('next', selector); },
                nextAll: function(selector) { return traverse('nextAll', selector); },
                nextUntil: function(selector, filterSel) { return traverse('nextUntil', selector, filterSel); },
                prev: function(selector) { return traverse('prev', selector); },
                prevAll: function(selector) { return traverse('prevAll', selector); },
                siblings: function(selector) { return traverse('siblings', selector); },
                contents: function() { return traverse('children', null); },
                clone: function() {
                  if (!arr.length) return wrapNodes([], sel, true);
                  var cloned = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domClone',
                    nodeIds: arr.map(function(n) { return n.nodeId; })
                  })) || '[]');
                  return wrapNodes(cloned, sel, false);
                },
                end: function() {
                  return prevSelection || wrapNodes([], null, true);
                },
                each: function(cb) {
                  arr.forEach(function(n, idx) {
                    var w = wrapNodes([n], sel, true);
                    cb.call(w, idx, w);
                  });
                  return sel;
                },
                map: function(cb) {
                  var out = arr.map(function(n, idx) {
                    var w = wrapNodes([n], sel, true);
                    return cb.call(w, idx, w);
                  });
                  var mappedSel = {
                    get: function() { return out; },
                    toArray: function() { return out; },
                    length: out.length
                  };
                  return mappedSel;
                },
                toArray: function() {
                  return arr.map(function(n) { return wrapNodes([n], sel, true); });
                },
                remove: function(selector) {
                  if (!arr.length) return sel;
                  __nuvioCall(JSON.stringify({
                    op: 'domRemove',
                    nodeIds: arr.map(function(n) { return n.nodeId; }),
                    selector: selector ? String(selector) : null
                  }));
                  domVersion++;
                  return sel;
                },
                empty: function() {
                  if (!arr.length) return sel;
                  __nuvioCall(JSON.stringify({
                    op: 'domEmpty',
                    nodeIds: arr.map(function(n) { return n.nodeId; })
                  }));
                  domVersion++;
                  return sel;
                }
              };

              function traverse(kind, selector, filterSel) {
                if (!arr.length) return wrapNodes([], sel, true);
                var items = JSON.parse(__nuvioCall(JSON.stringify({
                  op: 'domTraverse',
                  kind: kind,
                  nodeIds: arr.map(function(n) { return n.nodeId; }),
                  selector: selector ? String(selector) : null,
                  filter: filterSel ? String(filterSel) : null
                })) || '[]');
                return wrapNodes(items, sel, false);
              }

              if (!skipIndex) {
                for (var i = 0; i < arr.length; i++) {
                  sel[i] = wrapNodes([arr[i]], sel, true);
                }
              }

              if (typeof Symbol !== 'undefined' && Symbol.iterator) {
                sel[Symbol.iterator] = function() {
                  var idx = 0;
                  return {
                    next: function() {
                      if (idx < arr.length) {
                        return { value: wrapNodes([arr[idx++]], sel, true), done: false };
                      }
                      return { value: undefined, done: true };
                    }
                  };
                };
              }

              return sel;
            }

            var $ = function(selector, context) {
              if (!selector) return wrapNodes([], null, true);
              if (typeof selector === 'object') {
                if (selector._isCheerioSelection) return selector;
                if (typeof selector.nodeId === 'number') return wrapNodes([selector], null, false);
                if (typeof selector.text === 'function') return selector;
                return wrapNodes([], null, true);
              }
              var selStr = String(selector).trim();
              if (selStr.charAt(0) === '<' && selStr.indexOf('>') > 0) {
                var frag = createCheerioInstance(selStr);
                return frag('*');
              }
              if (context !== undefined && context !== null) {
                if (typeof context === 'object' && context._isCheerioSelection) {
                  return context.find(selStr);
                }
                if (typeof context === 'object' && typeof context.nodeId === 'number') {
                  return wrapNodes([context], null, true).find(selStr);
                }
                if (typeof context === 'string') {
                  return createCheerioInstance(context)(selStr);
                }
              }
              var items = JSON.parse(__nuvioCall(JSON.stringify({
                op: 'domSelect',
                docId: docId,
                selector: selStr,
                contextNodeIds: []
              })) || '[]');
              return wrapNodes(items, null, false);
            };
            $.html = function(el) {
              if (el && el._isCheerioSelection && el._nodes && el._nodes.length) {
                return ensureFresh(el._nodes[0]).outerHtml || '';
              }
              return String(html || '');
            };
            $.text = function(el) {
              return el && typeof el.text === 'function' ? el.text() : '';
            };
            $.root = function() {
              return $('html');
            };
            return $;
          }

          global.cheerio = {
            load: function(html) {
              return createCheerioInstance(html);
            }
          };
          global.cheerio.default = global.cheerio;

          function buildAxiosInstance(defaults) {
            defaults = defaults || {};
            function appendParams(targetUrl, params) {
              if (!params || typeof params !== 'object') return targetUrl;
              var usp = (params instanceof global.URLSearchParams) ? params : new global.URLSearchParams();
              if (!(params instanceof global.URLSearchParams)) {
                Object.keys(params).forEach(function(k) {
                  var v = params[k];
                  if (v !== undefined && v !== null) usp.append(k, String(v));
                });
              }
              var qs = usp.toString();
              if (!qs) return targetUrl;
              return targetUrl + (targetUrl.indexOf('?') >= 0 ? '&' : '?') + qs;
            }
            function resolveUrl(baseURL, u) {
              var raw = String(u || '');
              if (baseURL && !/^[a-zA-Z][a-zA-Z0-9+.-]*:\/\//.test(raw)) {
                return String(baseURL).replace(/\/+$/, '') + '/' + raw.replace(/^\/+/, '');
              }
              return raw;
            }
            function request(configOrUrl, maybeConfig) {
              var cfg = typeof configOrUrl === 'string'
                ? Object.assign({}, maybeConfig || {}, { url: configOrUrl })
                : Object.assign({}, configOrUrl || {});
              var baseURL = cfg.baseURL !== undefined ? cfg.baseURL : defaults.baseURL;
              var fullUrl = appendParams(resolveUrl(baseURL, cfg.url || ''), cfg.params);
              var method = String(cfg.method || 'GET').toUpperCase();
              var headers = Object.assign({}, defaults.headers || {}, cfg.headers || {});
              var body = cfg.data !== undefined ? cfg.data : cfg.body;
              var bodyStr = null;
              if (body !== undefined && body !== null && method !== 'GET' && method !== 'HEAD') {
                if (typeof body === 'string') {
                  bodyStr = body;
                } else if (body instanceof global.URLSearchParams) {
                  bodyStr = body.toString();
                  if (!headers['Content-Type'] && !headers['content-type']) {
                    headers['Content-Type'] = 'application/x-www-form-urlencoded';
                  }
                } else {
                  bodyStr = JSON.stringify(body);
                  if (!headers['Content-Type'] && !headers['content-type']) {
                    headers['Content-Type'] = 'application/json';
                  }
                }
              }
              var redirectMode = (cfg.maxRedirects === 0 || cfg.redirect === 'manual') ? 'manual' : 'follow';
              return global.fetch(fullUrl, {
                method: method,
                headers: headers,
                body: bodyStr,
                redirect: redirectMode,
                timeoutMs: Number(cfg.timeout || cfg.timeoutMs || defaults.timeout || 0)
              }).then(function(r) {
                return r.text().then(function(t) {
                  var data = t;
                  if (cfg.responseType !== 'text') {
                    try { data = JSON.parse(t); } catch (e) {}
                  }
                  var headersMap = {};
                  r.headers.entries().forEach(function(pair) {
                    headersMap[String(pair[0]).toLowerCase()] = pair[1];
                  });
                  var axResp = {
                    data: data,
                    status: r.status,
                    statusText: r.statusText,
                    headers: headersMap,
                    config: cfg,
                    request: { responseURL: r.url }
                  };
                  var validate = cfg.validateStatus || function(s) { return s >= 200 && s < 300; };
                  if (!validate(r.status)) {
                    var err = new Error('Request failed with status code ' + r.status);
                    err.isAxiosError = true;
                    err.response = axResp;
                    err.status = r.status;
                    throw err;
                  }
                  return axResp;
                });
              });
            }
            var fn = function(configOrUrl, maybeConfig) { return request(configOrUrl, maybeConfig); };
            fn.request = request;
            fn.defaults = defaults;
            fn.get = function(url, config) { return request(Object.assign({}, config || {}, { url: url, method: 'GET' })); };
            fn.delete = function(url, config) { return request(Object.assign({}, config || {}, { url: url, method: 'DELETE' })); };
            fn.head = function(url, config) { return request(Object.assign({}, config || {}, { url: url, method: 'HEAD' })); };
            fn.post = function(url, data, config) { return request(Object.assign({}, config || {}, { url: url, method: 'POST', data: data })); };
            fn.put = function(url, data, config) { return request(Object.assign({}, config || {}, { url: url, method: 'PUT', data: data })); };
            fn.patch = function(url, data, config) { return request(Object.assign({}, config || {}, { url: url, method: 'PATCH', data: data })); };
            fn.create = function(newDefaults) { return buildAxiosInstance(Object.assign({}, defaults, newDefaults || {})); };
            fn.isAxiosError = function(e) { return Boolean(e && e.isAxiosError); };
            fn.default = fn;
            return fn;
          }
          var axiosShim = buildAxiosInstance({});
          global.axios = axiosShim;
          global.htmlText = function(str) {
            var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'htmlText', html: String(str || '') })) || '{}');
            return res.text || '';
          };
          global.base64ToBytes = function(b64) {
            var bin = global.atob(String(b64 || ''));
            var out = new Uint8Array(bin.length);
            for (var i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
            return out;
          };

          function encodeCryptoOut(hexStr, encoding) {
            var wa = makeWordArray(hexStr);
            if (!encoding) return global.Buffer.from(hexStr, 'hex');
            var enc = String(encoding).toLowerCase();
            if (enc === 'hex') return hexStr;
            if (enc === 'base64') return CryptoJSImpl.enc.Base64.stringify(wa);
            if (enc === 'base64url') return CryptoJSImpl.enc.Base64url.stringify(wa);
            if (enc === 'latin1' || enc === 'binary') return CryptoJSImpl.enc.Latin1.stringify(wa);
            return CryptoJSImpl.enc.Utf8.stringify(wa);
          }

          function inputToHex(data, inputEnc) {
            if (data instanceof Uint8Array) return bytesToHex(data);
            var enc = String(inputEnc || 'utf8').toLowerCase();
            var str = String(data || '');
            if (enc === 'hex') return str;
            if (enc === 'base64' || enc === 'base64url') return global.Buffer.from(str, enc).toString('hex');
            if (enc === 'latin1' || enc === 'binary') return bytesToHex(latin1StrToBytes(str));
            return bytesToHex(utf8StrToBytes(str));
          }

          var nodeCryptoShim = {
            randomBytes: function(size) {
              var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoRandom', size: Number(size || 16) })) || '{}');
              return global.Buffer.from(res.hex || '', 'hex');
            },
            createHash: function(algo) {
              var a = String(algo || 'sha256').toLowerCase();
              var mapped = a === 'md5' ? 'MD5' : a === 'sha1' ? 'SHA-1' : a === 'sha512' ? 'SHA-512' : 'SHA-256';
              var chunksHex = '';
              return {
                update: function(data, enc) { chunksHex += inputToHex(data, enc); return this; },
                digest: function(outEnc) {
                  var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoDigest', algo: mapped, hex: chunksHex })) || '{}');
                  return encodeCryptoOut(res.hex || '', outEnc);
                }
              };
            },
            createHmac: function(algo, key) {
              var a = String(algo || 'sha256').toLowerCase();
              var mapped = a === 'md5' ? 'HmacMD5' : a === 'sha1' ? 'HmacSHA1' : a === 'sha512' ? 'HmacSHA512' : 'HmacSHA256';
              var keyHex = inputToHex(key, 'utf8');
              var chunksHex = '';
              return {
                update: function(data, enc) { chunksHex += inputToHex(data, enc); return this; },
                digest: function(outEnc) {
                  var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'cryptoHmac', algo: mapped, dataHex: chunksHex, keyHex: keyHex })) || '{}');
                  return encodeCryptoOut(res.hex || '', outEnc);
                }
              };
            },
            createCipheriv: function(algo, key, iv) {
              var mode = String(algo || 'aes-256-cbc').toUpperCase().indexOf('ECB') >= 0 ? 'ECB' :
                         String(algo || '').toUpperCase().indexOf('CTR') >= 0 ? 'CTR' : 'CBC';
              var keyHex = inputToHex(key, 'utf8');
              var ivHex = iv ? inputToHex(iv, 'utf8') : null;
              var chunksHex = '';
              return {
                setAutoPadding: function() { return this; },
                update: function(data, inEnc, outEnc) {
                  chunksHex += inputToHex(data, inEnc);
                  return outEnc ? '' : global.Buffer.alloc(0);
                },
                final: function(outEnc) {
                  var res = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'cryptoAes', action: 'encrypt', inputHex: chunksHex,
                    keyHex: keyHex, ivHex: ivHex, cipherMode: mode, padding: 'Pkcs7'
                  })) || '{}');
                  return encodeCryptoOut(res.hex || '', outEnc);
                }
              };
            },
            createDecipheriv: function(algo, key, iv) {
              var mode = String(algo || 'aes-256-cbc').toUpperCase().indexOf('ECB') >= 0 ? 'ECB' :
                         String(algo || '').toUpperCase().indexOf('CTR') >= 0 ? 'CTR' : 'CBC';
              var keyHex = inputToHex(key, 'utf8');
              var ivHex = iv ? inputToHex(iv, 'utf8') : null;
              var chunksHex = '';
              return {
                setAutoPadding: function() { return this; },
                update: function(data, inEnc, outEnc) {
                  chunksHex += inputToHex(data, inEnc);
                  return outEnc ? '' : global.Buffer.alloc(0);
                },
                final: function(outEnc) {
                  var res = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'cryptoAes', action: 'decrypt', inputHex: chunksHex,
                    keyHex: keyHex, ivHex: ivHex, cipherMode: mode, padding: 'Pkcs7'
                  })) || '{}');
                  return encodeCryptoOut(res.hex || '', outEnc);
                }
              };
            }
          };

          global.require = function(modName) {
            var name = String(modName || '').toLowerCase();
            if (name.indexOf('cheerio') >= 0) return global.cheerio;
            if (name === 'node-fetch' || name === 'cross-fetch' || name === 'undici') return global.fetch;
            if (name === 'crypto-js' || name.indexOf('crypto-js/') >= 0) return global.CryptoJS;
            if (name === 'crypto' || name === 'node:crypto') return nodeCryptoShim;
            if (name === 'axios') return axiosShim;
            if (name === 'querystring' || name === 'node:querystring') {
              return {
                stringify: function(obj) { return new global.URLSearchParams(obj).toString(); },
                parse: function(str) {
                  var p = new global.URLSearchParams(str);
                  var out = {};
                  p.forEach(function(v, k) { out[k] = v; });
                  return out;
                }
              };
            }
            if (name === 'url' || name === 'node:url') {
              return {
                URL: global.URL,
                URLSearchParams: global.URLSearchParams,
                parse: function(u) { try { return new global.URL(u); } catch (e) { return {}; } }
              };
            }
            return {};
          };

          global.__nuvioResult = function(payload) {
            __nuvioCall(JSON.stringify({ op: 'result', data: JSON.stringify(payload) }));
          };

          global.SCRAPER_SETTINGS = global.SCRAPER_SETTINGS || {};
          global.__kestrelCustomHeaders = global.__kestrelCustomHeaders || {};
          global.module = { exports: {} };
          global.exports = global.module.exports;
        })(globalThis);
    """.trimIndent()
}
