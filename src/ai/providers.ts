import { device } from '../platform/native';
/** Fixed-origin BYOK adapters. Keys are only authentication headers, never prompts or logs. */
export type ProviderId = 'openai' | 'anthropic';
export const PROVIDERS = [
  {
    id: 'openai' as const,
    name: 'OpenAI',
    host: 'api.openai.com',
    endpoint: 'https://api.openai.com/v1/chat/completions',
    defaultModel: 'gpt-4.1-mini',
    note: 'Legacy default; check availability and deprecation with your API account. Choose a Chat Completions model supporting tools and JSON.',
  },
  {
    id: 'anthropic' as const,
    name: 'Anthropic',
    host: 'api.anthropic.com',
    endpoint: 'https://api.anthropic.com/v1/messages',
    defaultModel: 'claude-haiku-4-5-20251001',
    note: 'Pinned Haiku example; account access and Messages tool support are required.',
  },
];
export type Json =
  | null
  | boolean
  | number
  | string
  | Json[]
  | { [key: string]: Json };
export type Obj = { [key: string]: Json };
export type ToolCall = { id: string; name: string; arguments: Obj };
export type Message = {
  role: 'system' | 'user' | 'assistant' | 'tool';
  content: string | null;
  toolCalls?: ToolCall[];
  toolCallId?: string;
};
export type Tool = { name: string; description: string; parameters: Obj };
export type ModelRequest = {
  model: string;
  messages: Message[];
  tools: Tool[];
  requireTools?: boolean;
};
export type ModelReply = { text: string; calls: ToolCall[] };
export type TransportRequest = {
  endpoint: string;
  headers: Readonly<Record<string, string>>;
  body: Obj;
  signal?: AbortSignal;
};
export type Transport = (request: TransportRequest) => Promise<unknown>;
export type Consent = {
  provider: ProviderId;
  processing: boolean;
  labels: boolean;
};
const MAX_REQUEST = 256000,
  MAX_RESPONSE = 128000;
const failed = () =>
  new Error(
    'The selected provider did not return an accepted response. No automatic retry or financial action was made.',
  );
export function object(value: unknown): Obj {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw failed();
  }
  return value as Obj;
}
export function only(value: Obj, keys: string[]): void {
  if (Object.keys(value).some(key => !keys.includes(key))) {
    throw failed();
  }
}
export function text(value: unknown, max = 16000): string {
  if (typeof value !== 'string' || value.length > max) {
    throw failed();
  }
  return value;
}
export function utf8Length(value: string): number {
  let size = 0;
  for (const char of value) {
    const code = char.codePointAt(0)!;
    size += code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
  }
  return size;
}
export function providerFor(id: ProviderId) {
  const provider = PROVIDERS.find(item => item.id === id);
  if (!provider) {
    throw new Error('Choose an implemented provider.');
  }
  return provider;
}
export function validateCredentials(
  provider: ProviderId,
  key: string,
  model: string,
  consent: Consent,
): void {
  providerFor(provider);
  if (
    !consent ||
    consent.provider !== provider ||
    consent.processing !== true
  ) {
    throw new Error(
      'Give fresh processing consent naming the selected provider.',
    );
  }
  if (typeof key !== 'string' || !/^[\x21-\x7e]{1,4000}$/.test(key.trim())) {
    throw new Error('Enter a valid key for the selected provider.');
  }
  if (typeof model !== 'string' || !/^[A-Za-z0-9._:-]{1,100}$/.test(model)) {
    throw new Error('Enter an account-supported model ID.');
  }
}
function aborted(signal?: AbortSignal) {
  if (signal?.aborted) {
    throw new Error('Request stopped. An in-flight request cannot be unsent.');
  }
}
export const httpTransport: Transport = async ({
  endpoint,
  headers,
  body,
  signal,
}) => {
  const provider = PROVIDERS.find(item => item.endpoint === endpoint);
  if (!provider) {
    throw new Error('Unsupported provider endpoint.');
  }
  const encoded = JSON.stringify(body);
  if (utf8Length(encoded) > MAX_REQUEST) {
    throw failed();
  }
  const header =
    provider.id === 'openai' ? headers.Authorization : headers['x-api-key'];
  const key =
    provider.id === 'openai' && header?.startsWith('Bearer ')
      ? header.slice(7)
      : provider.id === 'anthropic'
      ? header
      : '';
  if (!key || !/^[\x21-\x7e]{1,4000}$/.test(key)) {
    throw failed();
  }
  aborted(signal);
  try {
    // Native fixed-origin HTTPS disables redirects and enforces streamed byte bounds.
    // Cancellation rejects late acceptance; an in-flight provider request cannot be unsent.
    const raw = await device.providerRequest(provider.id, key, encoded);
    if (utf8Length(raw) > MAX_RESPONSE) {
      throw failed();
    }
    aborted(signal);
    return JSON.parse(raw) as unknown;
  } catch {
    throw failed();
  }
};

function validateRequest(request: ModelRequest): void {
  if (
    !/^[A-Za-z0-9._:-]{1,100}$/.test(request.model) ||
    request.messages.length < 2 ||
    request.messages.length > 40 ||
    request.tools.length > 16
  ) {
    throw failed();
  }
  const ids = new Set<string>(),
    pending = new Set<string>();
  let started = false;
  for (const message of request.messages) {
    if (message.role === 'system') {
      if (
        started ||
        typeof message.content !== 'string' ||
        message.toolCalls ||
        message.toolCallId
      ) {
        throw failed();
      }
      continue;
    }
    started = true;
    if (message.role === 'tool') {
      if (
        !message.toolCallId ||
        !pending.delete(message.toolCallId) ||
        typeof message.content !== 'string'
      ) {
        throw failed();
      }
      continue;
    }
    if (
      pending.size ||
      !['user', 'assistant'].includes(message.role) ||
      message.toolCallId ||
      (message.content !== null && typeof message.content !== 'string')
    ) {
      throw failed();
    }
    if (message.toolCalls) {
      if (
        message.role !== 'assistant' ||
        !message.toolCalls.length ||
        message.toolCalls.length > 8
      ) {
        throw failed();
      }
      for (const call of message.toolCalls) {
        if (
          !/^[A-Za-z0-9_-]{1,200}$/.test(call.id) ||
          ids.has(call.id) ||
          !/^[A-Za-z0-9_-]{1,64}$/.test(call.name)
        ) {
          throw failed();
        }
        object(call.arguments);
        ids.add(call.id);
        pending.add(call.id);
      }
    }
  }
  if (
    pending.size ||
    !['user', 'tool'].includes(
      request.messages[request.messages.length - 1].role,
    )
  ) {
    throw failed();
  }
}
export function protocolRequest(
  provider: ProviderId,
  request: ModelRequest,
): Obj {
  validateRequest(request);
  if (provider === 'openai') {
    return {
      model: request.model,
      store: false,
      max_completion_tokens: 800,
      response_format: { type: 'json_object' },
      messages: request.messages.map(message => ({
        role: message.role,
        content: message.content,
        ...(message.toolCalls
          ? {
              tool_calls: message.toolCalls.map(call => ({
                id: call.id,
                type: 'function',
                function: {
                  name: call.name,
                  arguments: JSON.stringify(call.arguments),
                },
              })),
            }
          : {}),
        ...(message.toolCallId ? { tool_call_id: message.toolCallId } : {}),
      })),
      ...(request.tools.length
        ? {
            tools: request.tools.map(tool => ({
              type: 'function',
              function: { ...tool, strict: true },
            })),
          }
        : {}),
      ...(request.requireTools ? { tool_choice: 'required' } : {}),
    };
  }
  providerFor(provider);
  const messages: Obj[] = [],
    systems: string[] = [];
  const append = (role: string, block: Obj) => {
    const last = messages[messages.length - 1];
    if (last?.role === role) {
      (last.content as Json[]).push(block);
    } else {
      messages.push({ role, content: [block] });
    }
  };
  for (const message of request.messages) {
    if (message.role === 'system') {
      systems.push(message.content!);
      continue;
    }
    if (message.role === 'tool') {
      const content = message.content!;
      let error = false;
      try {
        error = !!object(JSON.parse(content)).error;
      } catch {
        /* Read-only text result. */
      }
      append('user', {
        type: 'tool_result',
        tool_use_id: message.toolCallId!,
        content,
        ...(error ? { is_error: true } : {}),
      });
    } else {
      if (message.content) {
        append(message.role, { type: 'text', text: message.content });
      }
      for (const call of message.toolCalls ?? []) {
        append('assistant', {
          type: 'tool_use',
          id: call.id,
          name: call.name,
          input: call.arguments,
        });
      }
    }
  }
  return {
    model: request.model,
    max_tokens: 800,
    system: systems.join('\n'),
    messages,
    ...(request.tools.length
      ? {
          tools: request.tools.map(tool => ({
            name: tool.name,
            description: tool.description,
            input_schema: tool.parameters,
            strict: true,
          })),
        }
      : {}),
    ...(request.requireTools ? { tool_choice: { type: 'any' } } : {}),
  };
}
export function protocolReply(provider: ProviderId, raw: unknown): ModelReply {
  const response = object(raw),
    calls: ToolCall[] = [];
  let answer = '';
  if (provider === 'openai') {
    if (!Array.isArray(response.choices) || response.choices.length !== 1) {
      throw failed();
    }
    const choice = object(response.choices[0]),
      message = object(choice.message);
    if (
      message.role !== 'assistant' ||
      (message.refusal != null && message.refusal !== '')
    ) {
      throw failed();
    }
    if (message.tool_calls != null && !Array.isArray(message.tool_calls)) {
      throw failed();
    }
    for (const value of (message.tool_calls as Json[]) ?? []) {
      const call = object(value),
        fn = object(call.function);
      if (call.type !== 'function') {
        throw failed();
      }
      calls.push({
        id: text(call.id, 200),
        name: text(fn.name, 64),
        arguments: object(JSON.parse(text(fn.arguments, 4096))),
      });
    }
    if (choice.finish_reason !== (calls.length ? 'tool_calls' : 'stop')) {
      throw failed();
    }
    if (message.content != null) {
      answer = text(message.content);
    }
  } else {
    providerFor(provider);
    if (
      response.type !== 'message' ||
      response.role !== 'assistant' ||
      !Array.isArray(response.content)
    ) {
      throw failed();
    }
    for (const value of response.content) {
      const block = object(value);
      if (block.type === 'text') {
        answer += (answer ? '\n' : '') + text(block.text);
      } else if (block.type === 'tool_use') {
        calls.push({
          id: text(block.id, 200),
          name: text(block.name, 64),
          arguments: object(block.input),
        });
      } else {
        throw failed();
      }
    }
    if (
      calls.length
        ? response.stop_reason !== 'tool_use'
        : !['end_turn', 'stop_sequence'].includes(String(response.stop_reason))
    ) {
      throw failed();
    }
  }
  if (
    calls.length > 8 ||
    (!calls.length && !answer.trim()) ||
    answer.length > 16000 ||
    new Set(calls.map(call => call.id)).size !== calls.length ||
    calls.some(
      call =>
        !/^[A-Za-z0-9_-]{1,200}$/.test(call.id) ||
        !/^[A-Za-z0-9_-]{1,64}$/.test(call.name),
    )
  ) {
    throw failed();
  }
  return { text: answer, calls };
}
export async function complete(
  provider: ProviderId,
  key: string,
  request: ModelRequest,
  transport: Transport = httpTransport,
  signal?: AbortSignal,
): Promise<ModelReply> {
  const selected = providerFor(provider);
  aborted(signal);
  const body = protocolRequest(provider, request);
  const encoded = JSON.stringify(body);
  if (utf8Length(encoded) > MAX_REQUEST || encoded.includes(key.trim())) {
    throw failed();
  }
  try {
    const headers: Record<string, string> =
      provider === 'openai'
        ? {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${key.trim()}`,
          }
        : {
            'Content-Type': 'application/json',
            'x-api-key': key.trim(),
            'anthropic-version': '2026-10-21',
          };
    const response = await transport({
      endpoint: selected.endpoint,
      headers,
      body,
      signal,
    });
    aborted(signal);
    const encodedResponse = JSON.stringify(response);
    if (
      utf8Length(encodedResponse) > MAX_RESPONSE ||
      encodedResponse.includes(key.trim())
    ) {
      throw failed();
    }
    return protocolReply(provider, response);
  } catch {
    throw failed();
  }
}
