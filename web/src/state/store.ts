import { useSyncExternalStore } from 'react'

/** Minimal observable value, consumed with [useStore]. */
export class Store<T> {
  private listeners = new Set<() => void>()
  constructor(private value: T) {}

  get = (): T => this.value

  set(next: T | ((prev: T) => T)): void {
    const v = typeof next === 'function' ? (next as (p: T) => T)(this.value) : next
    if (Object.is(v, this.value)) return
    this.value = v
    this.listeners.forEach((l) => l())
  }

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }
}

export function useStore<T>(store: Store<T>): T {
  return useSyncExternalStore(store.subscribe, store.get)
}
