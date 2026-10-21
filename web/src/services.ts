// Application singletons.
import { HermesClient } from './api/hermes'
import { TurnRunner } from './chat/turnRunner'
import { HermesRepository } from './data/repository'
import { settings } from './settings/settingsStore'

export const client = new HermesClient(() => {
  const s = settings.get()
  return { baseUrl: s.baseUrl, apiKey: s.apiKey }
})
export const repo = new HermesRepository(client)
export const runner = new TurnRunner(repo, client)
