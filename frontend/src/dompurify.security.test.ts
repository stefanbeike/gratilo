import DOMPurify from 'dompurify'
import { afterEach, expect, it } from 'vitest'

afterEach(() => { DOMPurify.removeAllHooks(); document.body.replaceChildren() })

it('resolves a patched DOMPurify version', () => {
  const [major, minor, patch] = DOMPurify.version.split('.').map(Number)
  expect(major > 3 || (major === 3 && (minor > 4 || (minor === 4 && patch >= 17)))).toBe(true)
})

it('sanitizes attached shadow roots with template-safe DOM output', () => {
  const host = document.createElement('section')
  const root = host.attachShadow({mode:'open'})
  root.append(document.createTextNode('{'), document.createElement('script'), document.createTextNode('{danger}'), document.createElement('script'), document.createTextNode('}'))
  document.body.append(host)
  expect(root.textContent).toBe('{{danger}}')
  DOMPurify.sanitize(host, {SAFE_FOR_TEMPLATES:true, IN_PLACE:true})
  expect(root.textContent).not.toContain('{{')
})

it('does not leave unsafe handlers when an in-place hook detaches a parent', () => {
  const container = document.createElement('div')
  container.innerHTML = '<section><i>first</i><img src=x onerror=alert(1)></section><img src=x onerror=alert(2)>'
  document.body.append(container)
  const detached = container.querySelector('section')!
  DOMPurify.addHook('uponSanitizeElement', node => {
    if (node.nodeName === 'I') node.parentNode?.parentNode?.removeChild(node.parentNode)
  })
  DOMPurify.sanitize(container, {IN_PLACE:true})
  expect(container.querySelector('[onerror]')).toBeNull()
  expect(detached.querySelector('[onerror]')).toBeNull()
})
