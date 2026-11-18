/**
 * 「新建提供商」表单：一次 POST /api/custom-providers 把提供商与它的首个
 * 账号一起建出来（后端在同一请求里建账号，前端不拆两个请求）。
 *
 * 字段与桌面端同一套（词典键直接复用桌面端，缺译回落中文原文）：
 *   名称（1~64 字符）、协议（chat_completions / responses / anthropic，
 *   与后端 PROTOCOLS 逐字一致）、Base URL（OpenAI 系填到 /v1，Anthropic
 *   填根地址）、API Key、「该上游无需鉴权」勾选。
 *
 * 请求体只含 name / protocol / baseUrl，外加 apiKey 或 noAuth 之一：
 * 勾了 noAuth 就不传 apiKey（两者互斥，判据在存储层收口）。baseUrl 不抄进
 * 账号 —— 后端 create_custom_provider 会主动 strip 掉它，账号始终跟随
 * 提供商地址（issue #455 的教训）。
 */
import { useState } from 'react'
import {
  Button, Checkbox, Input, Label, Select, SelectContent, SelectItem, SelectTrigger,
  SelectValue, Sheet, SheetBody, SheetContent, SheetFooter, SheetHeader, SheetTitle,
} from '@ui'
import { CUSTOM_PROVIDER_PROTOCOLS, createCustomProvider, type CustomProviderProtocol } from '../api/custom-providers'
import { serverIdentity } from '../lib/servers'
import { store } from '../state/store'
import { t } from '../i18n'

const MAX_NAME_CHARS = 64

const PROTOCOL_LABELS: Record<CustomProviderProtocol, string> = {
  chat_completions: 'OpenAI（chat_completions）',
  responses: 'OpenAI（responses）',
  anthropic: 'Anthropic（anthropic）',
}

export function CustomProviderSheet({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const [name, setName] = useState('')
  const [protocol, setProtocol] = useState<CustomProviderProtocol>('chat_completions')
  const [baseUrl, setBaseUrl] = useState('')
  const [apiKey, setApiKey] = useState('')
  const [noAuth, setNoAuth] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const identity = serverIdentity()

  async function submit(): Promise<void> {
    if (busy) return
    const trimmedName = name.trim()
    if (!trimmedName) { setError(t('请填写名称')); return }
    if ([...trimmedName].length > MAX_NAME_CHARS) { setError(t('提供商名称过长（最多 64 个字符）')); return }
    if (!baseUrl.trim()) { setError(t('请填写 Base URL')); return }
    if (!noAuth && !apiKey.trim()) { setError(t('上游不要鉴权时留空，并勾选下面一项（留空又不勾会被当成未配置凭证）')); return }
    setBusy(true)
    setError('')
    try {
      // 互斥收口：勾了「无需鉴权」就不带 apiKey 键；反之不传 noAuth
      const result = await createCustomProvider(noAuth
        ? { name: trimmedName, protocol, baseUrl: baseUrl.trim(), noAuth: true }
        : { name: trimmedName, protocol, baseUrl: baseUrl.trim(), apiKey: apiKey.trim() })
      if (serverIdentity() !== identity) return
      // 响应里的 list 是最新账号快照；这里仍走既有的整页刷新路径，与
      // 「添加账号」成功后的口径一致（余额快照等一并到位）
      await store.refresh(true)
      if (serverIdentity() !== identity) return
      const label = result.provider?.name || trimmedName
      store.toast(t('✅ 已创建自定义提供商「{name}」并添加账号', { name: label }))
      if (store.get().sessionError) store.toast(t('账号已添加，但账号列表同步失败，请刷新重试。'), 'warn')
      onCreated()
    } catch (cause) {
      if (serverIdentity() === identity) setError(cause instanceof Error ? cause.message : String(cause))
    } finally {
      if (serverIdentity() === identity) setBusy(false)
    }
  }

  return <Sheet open onOpenChange={(open, details) => {
    if (open) return
    if (busy) { details.cancel(); return }
    onClose()
  }}>
    <SheetContent>
      <SheetHeader><SheetTitle>{t('新建自定义提供商')}</SheetTitle></SheetHeader>
      <SheetBody>
        <Label className='flex flex-col gap-2'>{t('站点名称')}
          <Input maxLength={MAX_NAME_CHARS} value={name} onChange={event => setName(event.currentTarget.value)}
            placeholder={t('如：智谱 GLM')} autoComplete='off' />
        </Label>
        <p className='text-xs leading-relaxed text-subtle'>{t('1~64 个字符，账号列表里按它分组显示')}</p>
        <Label className='flex flex-col gap-2'>{t('协议')}
          <Select value={protocol} onValueChange={value => setProtocol(value as CustomProviderProtocol)}>
            <SelectTrigger><SelectValue>{PROTOCOL_LABELS[protocol]}</SelectValue></SelectTrigger>
            <SelectContent>
              {CUSTOM_PROVIDER_PROTOCOLS.map(value => (
                <SelectItem key={value} value={value}>{PROTOCOL_LABELS[value]}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </Label>
        <Label className='flex flex-col gap-2'>Base URL
          <Input value={baseUrl} onChange={event => setBaseUrl(event.currentTarget.value)}
            placeholder={protocol === 'anthropic' ? 'https://api.anthropic.com' : 'https://api.openai.com/v1'}
            autoComplete='off' autoCapitalize='off' autoCorrect='off' spellCheck={false} />
        </Label>
        <p className='text-xs leading-relaxed text-subtle'>{t('OpenAI 兼容填到 /v1；Anthropic 填根地址')}</p>
        <Label className='flex items-center gap-2 text-xs font-normal'>
          <Checkbox checked={noAuth} onCheckedChange={value => setNoAuth(value === true)} />
          {t('该上游无需鉴权（不发送鉴权头）')}
        </Label>
        {noAuth ? (
          <p className='text-xs leading-relaxed text-subtle'>{t('不需要 Key：转发与拉取模型都不发送鉴权头')}</p>
        ) : (
          <Label className='flex flex-col gap-2'>API Key
            <Input type='password' value={apiKey} onChange={event => setApiKey(event.currentTarget.value)}
              placeholder={t('粘贴 API Key')} autoComplete='new-password' autoCapitalize='off'
              autoCorrect='off' spellCheck={false} />
          </Label>
        )}
        <p className='text-xs leading-relaxed text-subtle'>{t('创建这个提供商，并同时建立它的第一个账号。')}</p>
        {error ? <p className='text-xs leading-relaxed text-destructive'>{error}</p> : null}
      </SheetBody>
      <SheetFooter>
        <Button variant='outline' disabled={busy} onClick={onClose}>{t('取消')}</Button>
        <Button disabled={busy} onClick={() => void submit()}>{busy ? t('保存中…') : t('创建并添加账号')}</Button>
      </SheetFooter>
    </SheetContent>
  </Sheet>
}
