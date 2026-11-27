// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

/**
 * Browser-like JavaScript host polyfill injected into QuickJS before running Nuvio scrapers:
 * - fetch(url, options), Headers, AbortController, axios shim
 * - Stateful Jsoup-backed cheerio.load(html) supporting $(sel, ctx), filter, closest, parent, next, prev, siblings, etc.
 * - Native-backed CryptoJS (AES CBC/ECB/CTR + OpenSSL EVP_BytesToKey, MD5, SHA1/256/512, HMAC, enc.Utf8/Base64/Hex/Latin1)
 * - URL, URLSearchParams, TextEncoder, TextDecoder, Buffer, atob/btoa, console, setTimeout/clearTimeout
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

          global.setTimeout = function(fn, ms) {
            var args = Array.prototype.slice.call(arguments, 2);
            Promise.resolve().then(function() {
              return __nuvioSleepAsync(ms || 0);
            }).then(function() {
              if (typeof fn === 'function') fn.apply(null, args);
            });
            return 1;
          };
          global.clearTimeout = function() {};
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
                removeEventListener: function() {}
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
                var b = String(base);
                var originMatch = b.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:\/\/[^\/?#]+)/);
                var origin = originMatch ? originMatch[1] : '';
                if (raw.indexOf('//') === 0) {
                  var protoMatch = b.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)/);
                  raw = (protoMatch ? protoMatch[1] : 'https:') + raw;
                } else if (raw.indexOf('/') === 0) {
                  raw = origin + raw;
                } else {
                  var basePath = b.split('?')[0].split('#')[0];
                  raw = basePath.replace(/\/[^\/]*$/, '/') + raw;
                }
              }
              var m = raw.match(/^([a-zA-Z][a-zA-Z0-9+.-]*:)\/\/([^\/?#:]+(?::\d+)?)(\/[^?#]*)?(\?[^#]*)?(#.*)?$/);
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
          }

          global.fetch = async function(url, opts) {
            opts = opts || {};
            var mergedHeaders = {};
            var custom = global.__kestrelCustomHeaders || {};
            for (var ck in custom) {
              if (Object.prototype.hasOwnProperty.call(custom, ck)) {
                mergedHeaders[ck] = custom[ck];
              }
            }
            if (opts.headers) {
              if (typeof opts.headers.forEach === 'function') {
                opts.headers.forEach(function(v, k) { mergedHeaders[k] = v; });
              } else {
                for (var k in opts.headers) {
                  if (Object.prototype.hasOwnProperty.call(opts.headers, k)) {
                    mergedHeaders[k] = opts.headers[k];
                  }
                }
              }
            }
            var bodyPayload = null;
            if (opts.body !== undefined && opts.body !== null) {
              bodyPayload = (opts.body instanceof global.URLSearchParams) ? opts.body.toString() : String(opts.body);
            }
            var payload = {
              url: String(url),
              method: (opts.method || 'GET').toUpperCase(),
              headers: mergedHeaders,
              body: bodyPayload,
              redirect: opts.redirect || 'follow'
            };
            var raw = await __nuvioFetchAsync(JSON.stringify(payload));
            var res = JSON.parse(raw);
            if (res.error) throw new Error(res.error);
            var bodyText = res.body || '';
            return {
              ok: res.status >= 200 && res.status < 300,
              status: res.status,
              statusText: res.statusText || '',
              url: res.url || payload.url,
              redirected: Boolean(res.redirected),
              headers: {
                get: function(name) {
                  var lower = String(name).toLowerCase();
                  var h = res.headers || {};
                  for (var hk in h) {
                    if (hk.toLowerCase() === lower) return h[hk];
                  }
                  return null;
                },
                has: function(name) { return this.get(name) !== null; },
                entries: function() {
                  var h = res.headers || {};
                  return Object.keys(h).map(function(k) { return [k, h[k]]; });
                }
              },
              text: async function() { return bodyText; },
              json: async function() { return JSON.parse(bodyText); }
            };
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
              }
            },
            mode: { CBC: 'CBC', ECB: 'ECB', CTR: 'CTR', CFB: 'CFB', OFB: 'OFB' },
            pad: { Pkcs7: 'Pkcs7', ZeroPadding: 'ZeroPadding', NoPadding: 'NoPadding' },
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

            function wrapNodes(nodes, prevSelection) {
              var arr = Array.isArray(nodes) ? nodes : [];
              var sel = {
                _isCheerioSelection: true,
                _nodes: arr,
                _prev: prevSelection || null,
                length: arr.length,
                text: function() {
                  return arr.map(function(n) { return n.text || ''; }).join('');
                },
                html: function() {
                  return arr.length ? (arr[0].html || '') : null;
                },
                attr: function(name, val) {
                  if (!arr.length) return undefined;
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
                first: function() { return wrapNodes(arr.length ? [arr[0]] : [], sel); },
                last: function() { return wrapNodes(arr.length ? [arr[arr.length - 1]] : [], sel); },
                eq: function(i) {
                  var idx = i < 0 ? arr.length + i : i;
                  return wrapNodes(arr[idx] ? [arr[idx]] : [], sel);
                },
                slice: function(start, end) {
                  return wrapNodes(arr.slice(start, end), sel);
                },
                get: function(i) {
                  if (i === undefined) return arr.map(function(n) { return wrapNodes([n], sel); });
                  var idx = i < 0 ? arr.length + i : i;
                  return arr[idx] ? wrapNodes([arr[idx]], sel) : undefined;
                },
                find: function(sub) {
                  if (!arr.length || !sub) return wrapNodes([], sel);
                  var found = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domSelect',
                    docId: docId,
                    selector: String(sub),
                    contextNodeIds: arr.map(function(n) { return n.nodeId; })
                  })) || '[]');
                  return wrapNodes(found, sel);
                },
                filter: function(matcher) {
                  if (!arr.length) return wrapNodes([], sel);
                  if (typeof matcher === 'function') {
                    var matched = arr.filter(function(n, idx) {
                      var w = wrapNodes([n], sel);
                      return Boolean(matcher.call(w, idx, w));
                    });
                    return wrapNodes(matched, sel);
                  }
                  var found = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domFilter',
                    nodeIds: arr.map(function(n) { return n.nodeId; }),
                    selector: String(matcher || ''),
                    invert: false
                  })) || '[]');
                  return wrapNodes(found, sel);
                },
                not: function(matcher) {
                  if (!arr.length) return wrapNodes([], sel);
                  if (typeof matcher === 'function') {
                    var kept = arr.filter(function(n, idx) {
                      var w = wrapNodes([n], sel);
                      return !matcher.call(w, idx, w);
                    });
                    return wrapNodes(kept, sel);
                  }
                  var found = JSON.parse(__nuvioCall(JSON.stringify({
                    op: 'domFilter',
                    nodeIds: arr.map(function(n) { return n.nodeId; }),
                    selector: String(matcher || ''),
                    invert: true
                  })) || '[]');
                  return wrapNodes(found, sel);
                },
                children: function(selector) {
                  return traverse('children', selector);
                },
                parent: function(selector) {
                  return traverse('parent', selector);
                },
                parents: function(selector) {
                  return traverse('parents', selector);
                },
                closest: function(selector) {
                  return traverse('closest', selector);
                },
                next: function(selector) {
                  return traverse('next', selector);
                },
                nextAll: function(selector) {
                  return traverse('nextAll', selector);
                },
                prev: function(selector) {
                  return traverse('prev', selector);
                },
                prevAll: function(selector) {
                  return traverse('prevAll', selector);
                },
                siblings: function(selector) {
                  return traverse('siblings', selector);
                },
                contents: function() {
                  return traverse('children', null);
                },
                clone: function() {
                  return wrapNodes(arr.slice(), sel);
                },
                end: function() {
                  return prevSelection || wrapNodes([], null);
                },
                each: function(cb) {
                  arr.forEach(function(n, idx) {
                    var w = wrapNodes([n], sel);
                    cb.call(w, idx, w);
                  });
                  return sel;
                },
                map: function(cb) {
                  var out = arr.map(function(n, idx) {
                    var w = wrapNodes([n], sel);
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
                  return arr.map(function(n) { return wrapNodes([n], sel); });
                }
              };

              function traverse(kind, selector) {
                if (!arr.length) return wrapNodes([], sel);
                var items = JSON.parse(__nuvioCall(JSON.stringify({
                  op: 'domTraverse',
                  kind: kind,
                  nodeIds: arr.map(function(n) { return n.nodeId; }),
                  selector: selector ? String(selector) : null
                })) || '[]');
                return wrapNodes(items, sel);
              }

              for (var i = 0; i < arr.length; i++) {
                sel[i] = wrapNodes([arr[i]], sel);
              }
              return sel;
            }

            var $ = function(selector, context) {
              if (!selector) return wrapNodes([], null);
              if (typeof selector === 'object') {
                if (selector._isCheerioSelection) return selector;
                if (typeof selector.nodeId === 'number') return wrapNodes([selector], null);
                if (typeof selector.text === 'function') return selector;
                return wrapNodes([], null);
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
                  return wrapNodes([context], null).find(selStr);
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
              return wrapNodes(items, null);
            };
            $.html = function(el) {
              if (el && el._isCheerioSelection && el._nodes && el._nodes.length) {
                return el._nodes[0].outerHtml || '';
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

          var axiosShim = {
            get: function(url, config) {
              config = config || {};
              return global.fetch(url, { method: 'GET', headers: config.headers || {} }).then(function(r) {
                return r.text().then(function(t) {
                  var data = t;
                  try { data = JSON.parse(t); } catch (e) {}
                  return { data: data, status: r.status, statusText: r.statusText, headers: r.headers };
                });
              });
            },
            post: function(url, body, config) {
              config = config || {};
              var headers = config.headers || {};
              var payload = typeof body === 'string' ? body : JSON.stringify(body || {});
              return global.fetch(url, { method: 'POST', headers: headers, body: payload }).then(function(r) {
                return r.text().then(function(t) {
                  var data = t;
                  try { data = JSON.parse(t); } catch (e) {}
                  return { data: data, status: r.status, statusText: r.statusText, headers: r.headers };
                });
              });
            }
          };

          global.require = function(modName) {
            var name = String(modName || '').toLowerCase();
            if (name.indexOf('cheerio') >= 0) return global.cheerio;
            if (name === 'node-fetch' || name === 'cross-fetch' || name === 'undici') return global.fetch;
            if (name === 'crypto-js' || name.indexOf('crypto-js/') >= 0) return global.CryptoJS;
            if (name === 'axios') return axiosShim;
            if (name === 'querystring') {
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
            if (name === 'url') {
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
