import {Decoder, Encoder} from 'cbor-x'
import {invoke} from '@tauri-apps/api/core'

const encoder = new Encoder({useRecords: false, variableMapSize: true})
const decoder = new Decoder()

export const Kitsune = {
  call: async (name: string, data: any) => {
    const payload: Uint8Array = encoder.encode(data)
    const rawResponse: ArrayBuffer = await invoke('call_kt', payload, {headers: {'x-fn': name}})

    // 3. Wrap the ArrayBuffer in a Uint8Array view so cbor-x can read it
    const responseBytes = new Uint8Array(rawResponse)

    // 4. Decode and return the JavaScript object
    return decoder.decode(responseBytes)
  }
}
