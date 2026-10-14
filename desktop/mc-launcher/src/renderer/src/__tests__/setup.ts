/**
 * jsdom is missing a few browser APIs Vuetify's layout code touches. The stubs
 * only need to exist; the tests never assert on layout measurements.
 */
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}

if (!('ResizeObserver' in globalThis)) {
  ;(globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = ResizeObserverStub
}

if (!('visualViewport' in window)) {
  ;(window as unknown as { visualViewport: unknown }).visualViewport = {
    width: 1024,
    height: 768,
    offsetLeft: 0,
    offsetTop: 0,
    scale: 1,
    addEventListener: () => {},
    removeEventListener: () => {}
  }
}

if (!('matchMedia' in window)) {
  ;(window as unknown as { matchMedia: unknown }).matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  })
}
