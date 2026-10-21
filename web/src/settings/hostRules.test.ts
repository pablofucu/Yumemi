import { describe, expect, it } from 'vitest'
import { hostOf, isInsecure, isMixedContent, isPrivateOrTailscaleHost, normalizeBaseUrl } from './hostRules'

describe('hostRules', () => {
  it('extracts hosts', () => {
    expect(hostOf('https://User:pw@Box.Tail1234.ts.net:8642/path?x=1')).toBe('box.tail1234.ts.net')
    expect(hostOf('100.64.0.1:8642/')).toBe('100.64.0.1')
    expect(hostOf('http://[::1]:8642')).toBe('::1')
    expect(hostOf('')).toBe('')
  })

  it('recognises private and Tailscale hosts', () => {
    const yes = ['localhost', 'foo.localhost', '::1', 'box.tail1234.ts.net', '127.0.0.1', '10.1.2.3', '192.168.1.5', '172.16.0.1', '172.31.255.255', '100.64.0.1', '100.127.255.255']
    const no = ['', 'example.com', '35.1.2.3', '172.15.0.1', '172.32.0.1', '100.63.0.1', '100.128.0.1', '300.1.1.1', '1.2.3']
    for (const h of yes) expect(isPrivateOrTailscaleHost(h), h).toBe(true)
    for (const h of no) expect(isPrivateOrTailscaleHost(h), h).toBe(false)
  })

  it('flags http:// on public hosts as insecure', () => {
    expect(isInsecure('http://100.101.102.103:8642')).toBe(false)
    expect(isInsecure('http://box.tail1234.ts.net:8642')).toBe(false)
    expect(isInsecure('http://192.168.1.5:8642')).toBe(false)
    expect(isInsecure('http://172.20.0.1')).toBe(false)
    expect(isInsecure('http://localhost:8642')).toBe(false)
    expect(isInsecure('https://example.com')).toBe(false)
    expect(isInsecure('http://35.1.2.3:8642')).toBe(true)
    expect(isInsecure('http://172.32.0.1')).toBe(true)
    expect(isInsecure('http://100.128.0.1')).toBe(true)
  })

  it('normalizes base URLs', () => {
    expect(normalizeBaseUrl('100.64.0.1:8642/')).toBe('http://100.64.0.1:8642')
    expect(normalizeBaseUrl('  https://x.example.com///  ')).toBe('https://x.example.com')
    expect(normalizeBaseUrl('hermes.example.com')).toBe('https://hermes.example.com')
    expect(normalizeBaseUrl('   ')).toBe('')
  })

  it('prefers https for private hosts when the page itself is https', () => {
    expect(normalizeBaseUrl('box.tail1234.ts.net', { pageIsHttps: true })).toBe('https://box.tail1234.ts.net')
    expect(normalizeBaseUrl('192.168.1.5:8642', { pageIsHttps: true })).toBe('https://192.168.1.5:8642')
    // Loopback is allowed over http from an https page.
    expect(normalizeBaseUrl('localhost:8642', { pageIsHttps: true })).toBe('http://localhost:8642')
    // An explicit scheme is never rewritten.
    expect(normalizeBaseUrl('http://192.168.1.5:8642', { pageIsHttps: true })).toBe('http://192.168.1.5:8642')
  })

  it('detects mixed content', () => {
    expect(isMixedContent('http://100.64.0.1:8642', 'https:')).toBe(true)
    expect(isMixedContent('http://box.tail.ts.net', 'https:')).toBe(true)
    expect(isMixedContent('https://box.tail.ts.net', 'https:')).toBe(false)
    expect(isMixedContent('http://localhost:8642', 'https:')).toBe(false)
    expect(isMixedContent('http://127.0.0.1:8642', 'https:')).toBe(false)
    expect(isMixedContent('http://100.64.0.1:8642', 'http:')).toBe(false)
  })
})
