// SPDX-License-Identifier: GPL-3.0-or-later
package com.kestrel.pluginhost.nuvio

/**
 * Browser-like JavaScript host polyfill injected into QuickJS before running Nuvio scrapers:
 * - fetch(url, options) -> Promise<{ ok, status, statusText, headers, url, text(), json() }>
 * - cheerio.load(html) & require('cheerio-without-node-native' | 'cheerio')
 * - URL, URLSearchParams, AbortController, Buffer, atob/btoa, console, setTimeout/clearTimeout
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
            error: function() { __nuvioCall(JSON.stringify({ op: 'log', message: '[ERR] ' + Array.prototype.slice.call(arguments).join(' ') })); }
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

          global.btoa = function(str) {
            var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'btoa', value: String(str) })) || '{}');
            return res.value || '';
          };
          global.atob = function(str) {
            var res = JSON.parse(__nuvioCall(JSON.stringify({ op: 'atob', value: String(str) })) || '{}');
            return res.value || '';
          };

          if (typeof global.Buffer === 'undefined') {
            global.Buffer = {
              from: function(input, encoding) {
                var enc = String(encoding || 'utf8').toLowerCase();
                var text = enc === 'base64' ? global.atob(String(input || '')) : String(input || '');
                return {
                  toString: function(outEnc) {
                    var target = String(outEnc || 'utf8').toLowerCase();
                    if (target === 'base64') return global.btoa(text);
                    return text;
                  }
                };
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
              for (var k in opts.headers) {
                if (Object.prototype.hasOwnProperty.call(opts.headers, k)) {
                  mergedHeaders[k] = opts.headers[k];
                }
              }
            }
            var payload = {
              url: String(url),
              method: (opts.method || 'GET').toUpperCase(),
              headers: mergedHeaders,
              body: opts.body !== undefined && opts.body !== null ? String(opts.body) : null
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
              headers: {
                get: function(name) {
                  var lower = String(name).toLowerCase();
                  var h = res.headers || {};
                  for (var hk in h) {
                    if (hk.toLowerCase() === lower) return h[hk];
                  }
                  return null;
                }
              },
              text: async function() { return bodyText; },
              json: async function() { return JSON.parse(bodyText); }
            };
          };

          function makeSelection(html, selector) {
            var items = JSON.parse(__nuvioCall(JSON.stringify({
              op: 'domSelect',
              html: String(html || ''),
              selector: String(selector || '')
            })) || '[]');

            function wrap(nodes) {
              var sel = {
                length: nodes.length,
                text: function() { return nodes.map(function(n) { return n.text || ''; }).join(''); },
                html: function() { return nodes.length ? (nodes[0].html || '') : ''; },
                attr: function(name) { return nodes.length && nodes[0].attrs ? nodes[0].attrs[name] : undefined; },
                hasClass: function(cls) {
                  var c = sel.attr('class') || '';
                  return c.split(/\s+/).indexOf(cls) >= 0;
                },
                first: function() { return wrap(nodes.length ? [nodes[0]] : []); },
                last: function() { return wrap(nodes.length ? [nodes[nodes.length - 1]] : []); },
                eq: function(i) { return wrap(nodes[i] ? [nodes[i]] : []); },
                find: function(sub) {
                  var combined = [];
                  nodes.forEach(function(n) {
                    var found = JSON.parse(__nuvioCall(JSON.stringify({
                      op: 'domSelect',
                      html: n.outerHtml || n.html || '',
                      selector: String(sub)
                    })) || '[]');
                    combined = combined.concat(found);
                  });
                  return wrap(combined);
                },
                each: function(cb) {
                  nodes.forEach(function(n, idx) { cb.call(wrap([n]), idx, wrap([n])); });
                  return sel;
                },
                map: function(cb) {
                  var out = nodes.map(function(n, idx) { return cb.call(wrap([n]), idx, wrap([n])); });
                  return { get: function() { return out; } };
                },
                toArray: function() { return nodes.map(function(n) { return wrap([n]); }); }
              };
              return sel;
            }
            return wrap(items);
          }

          global.cheerio = {
            load: function(html) {
              var $ = function(selector) {
                if (selector && typeof selector === 'object' && selector.text) return selector;
                return makeSelection(html, selector);
              };
              $.html = function() { return String(html || ''); };
              return $;
            }
          };

          global.require = function(modName) {
            var name = String(modName || '').toLowerCase();
            if (name.indexOf('cheerio') >= 0) return global.cheerio;
            if (name === 'node-fetch' || name === 'cross-fetch') return global.fetch;
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
