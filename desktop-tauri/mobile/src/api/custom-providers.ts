/**
 * 自定义提供商管理（「新建提供商」入口）。
 *
 * 后端契约见 server/api/custom_providers.rs 的模块头；这里只做 HTTP 形状的
 * 翻译，校验（名称 1~64 字符、协议三选一、baseUrl 归一、apiKey 与 noAuth
 * 互斥）都在后端 core 层收口，前端不复制判据。
 *
 * 「新建」一次事务式地带出首个账号：前端只发一条 POST /api/custom-providers，
 * 账号由后端在同一请求里建（baseUrl 会被后端 strip 掉，不抄进账号 —— 账号
 * 不写 baseUrl 键 = 始终跟随提供商，见 create_custom_provider 的说明）。
 */
import { apiCall } from './client'

/** 与后端 core::custom_providers::PROTOCOLS 逐字一致 */
export const CUSTOM_PROVIDER_PROTOCOLS = ['chat_completions', 'responses', 'anthropic'] as const
export type CustomProviderProtocol = (typeof CUSTOM_PROVIDER_PROTOCOLS)[number]

export type CustomProvider = {
  id: string
  name?: string
  protocol?: CustomProviderProtocol
  baseUrl?: string
  enabled?: boolean
  models?: Array<Record<string, unknown>>
  mappings?: Array<Record<string, unknown>>
  createdAt?: number
  [key: string]: unknown
}

export type CreateCustomProviderInput = {
  name: string
  protocol: CustomProviderProtocol
  baseUrl: string
  /** 与 noAuth 互斥：勾了「无需鉴权」就不要传这个键 */
  apiKey?: string
  /** 显式声明「该上游无需鉴权」（布尔）；为 true 时不得带 apiKey */
  noAuth?: boolean
}

export type CreateCustomProviderResult = {
  provider?: CustomProvider
  account?: { id?: string; provider?: string; [key: string]: unknown }
  created?: boolean
  /** 后端顺手带回的最新账号快照（与 GET /api/accounts 同一形状） */
  list?: unknown
}

export function listCustomProviders(): Promise<{ providers?: CustomProvider[] }> {
  return apiCall('/api/custom-providers')
}

export function createCustomProvider(input: CreateCustomProviderInput): Promise<CreateCustomProviderResult> {
  return apiCall('/api/custom-providers', { method: 'POST', body: input })
}

export function updateCustomProvider(
  id: string,
  patch: { name?: string; protocol?: CustomProviderProtocol; baseUrl?: string; enabled?: boolean },
): Promise<{ provider?: CustomProvider }> {
  return apiCall('/api/custom-providers/update', { method: 'POST', body: { id, ...patch } })
}

export function removeCustomProvider(id: string): Promise<{
  removed?: number
  accountsRemoved?: number
  keysUpdated?: number
  keysUnrestricted?: number
  list?: unknown
}> {
  return apiCall('/api/custom-providers/remove', { method: 'POST', body: { id } })
}

export function setCustomProviderModels(
  providerId: string,
  models: Array<Record<string, unknown>>,
  mappings: Array<Record<string, unknown>>,
): Promise<{ provider?: CustomProvider }> {
  return apiCall('/api/custom-providers/models', { method: 'POST', body: { providerId, models, mappings } })
}

/** 服务端代理拉取上游模型清单（不落盘，待确认后走 /models 保存） */
export function fetchCustomProviderModels(providerId: string): Promise<{ models?: string[] }> {
  return apiCall('/api/custom-providers/fetch-models', { method: 'POST', body: { providerId } })
}
