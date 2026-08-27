import {Decoder, Encoder} from 'cbor-x'
import {Channel, invoke} from '@tauri-apps/api/core'

/**
 * Bytes the ordinal takes at the head of an inbound event frame.
 *
 * Mirrors `ORDINAL_BYTES` in `src-tauri/src/wire.rs`, and applies to that
 * direction only: events pushed down the pump arrive on a channel that carries
 * nothing but bytes, so there is nowhere else to put the ordinal. Outbound, it
 * rides in a header; see `headerOf`.
 */
const ORDINAL_BYTES = 2

const encoder = new Encoder({useRecords: false, variableMapSize: true})
const decoder = new Decoder()

/** Stops a subscription started with `listen`. */
export type Unlisten = () => void

type Handler = (event: unknown) => void

/**
 * Subscribers by event ordinal.
 *
 * Sparse on purpose: an event nobody listens for has no row, and the pump can
 * drop its frame after two array loads without decoding anything.
 */
const subscribers: Array<Handler[] | undefined> = []

/** Ordinals as header values, built once each and reused forever after. */
const headerValues: string[] = []

/**
 * The ordinal as a header value.
 *
 * Outbound payloads go to Tauri exactly as cbor-x encoded them (no prefix, so
 * no second buffer and no copy), and the ordinal travels in a header instead.
 * That is close to free: Tauri's IPC constructs a `Headers` map on every call
 * regardless (`Content-Type`, `Tauri-Callback`, `Tauri-Error`,
 * `Tauri-Invoke-Key`), so one more entry rides on machinery already paid for.
 *
 * Memoised so the number is stringified once per ordinal for the life of the
 * page rather than once per call.
 */
function headerOf(ordinal: number): string {
  return headerValues[ordinal] ?? (headerValues[ordinal] = String(ordinal))
}

/** Zero bytes is how a `Unit`-returning export replies; there is nothing to decode. */
function reply(raw: ArrayBuffer): unknown {
  return raw.byteLength === 0 ? undefined : decoder.decode(new Uint8Array(raw))
}

/**
 * Hands an event to every local subscriber.
 *
 * Iterated over a copy: a handler is allowed to unsubscribe itself, and splicing
 * the array being walked would skip whichever handler followed it.
 */
function deliver(ordinal: number, event: unknown): void {
  const row = subscribers[ordinal]
  if (row === undefined || row.length === 0) return
  for (const handler of row.slice()) handler(event)
}

export const Bridge = {
  /** Calls the Kotlin export at `ordinal` and resolves with its decoded result. */
  call: async (ordinal: number, args: unknown): Promise<any> =>
    reply(
      await invoke<ArrayBuffer>('call_kt', encoder.encode(args), {
        headers: {'x-fn': headerOf(ordinal)},
      }),
    ),

  /**
   * Raises an event: Kotlin's `@Listener`s run, and so do the local ones.
   *
   * The local half is not a round trip. Kotlin never echoes a frontend-raised
   * event back; that is what `EventSource.JAVASCRIPT` suppresses, and it has to,
   * or every emit would come home again. So the frontend's own subscribers are
   * this side's job to notify, and they get the object as passed rather than a
   * decode of it.
   *
   * The send is started first so a slow local handler cannot delay Kotlin.
   */
  emit: (ordinal: number, event: unknown): Promise<void> => {
    const sent = invoke<void>('dispatch_event', encoder.encode(event), {
      headers: {'x-event': headerOf(ordinal)},
    })
    deliver(ordinal, event)
    return sent
  },

  /**
   * Subscribes to the event at `ordinal`; call the result to stop.
   *
   * Local bookkeeping only (the pump is registered once, for every event at
   * once), so this is synchronous and cannot fail.
   */
  listen: (ordinal: number, handler: Handler): Unlisten => {
    const row = subscribers[ordinal] ?? (subscribers[ordinal] = [])
    row.push(handler)
    return () => {
      const at = row.indexOf(handler)
      if (at >= 0) row.splice(at, 1)
    }
  },
}

/** The one channel every Kotlin-raised event arrives on. */
const pump = new Channel<ArrayBuffer>()

pump.onmessage = (message) => {
  const bytes = new Uint8Array(message)
  const ordinal = bytes[0] | (bytes[1] << 8)
  // Decoded only if somebody is listening; `deliver` would drop it otherwise.
  if (subscribers[ordinal]?.length) {
    deliver(ordinal, decoder.decode(bytes.subarray(ORDINAL_BYTES)))
  }
}

void invoke('register_events_pump', {pump})
