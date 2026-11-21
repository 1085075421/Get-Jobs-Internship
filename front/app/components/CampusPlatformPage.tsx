'use client'

import { useCallback, useEffect, useState } from 'react'
import { BiBarChart, BiBriefcase, BiPlay, BiSave, BiStop, BiRefresh, BiPlus, BiTrash } from 'react-icons/bi'
import { API_BASE } from '@/lib/api'
import PageHeader from '@/app/components/PageHeader'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs'

type Platform = 'shixiseng' | 'nowcoder'
type Config = { keywords: string; city: string; sortOrder?: 'default' | 'latest'; resumeIndex?: number }
type Stats = { total: number; delivered: number; failed: number; pending: number; todayDelivered: number }
type JobRow = {
  id: number; jobId: string; jobTitle: string; companyName: string; jobLink: string
  salary: string; location: string; deliveryStatus: string; failureReason?: string; deliveredAt?: string
}
type BlacklistItem = { id: number; type: 'company' | 'job'; value: string }

const emptyStats: Stats = { total: 0, delivered: 0, failed: 0, pending: 0, todayDelivered: 0 }

export default function CampusPlatformPage({ platform }: { platform: Platform }) {
  const displayName = platform === 'shixiseng' ? '实习僧' : '牛客网'
  const base = `${API_BASE}/api/campus/${platform}`
  const [config, setConfig] = useState<Config>({ keywords: '', city: '', sortOrder: 'latest', resumeIndex: 1 })
  const [stats, setStats] = useState<Stats>(emptyStats)
  const [rows, setRows] = useState<JobRow[]>([])
  const [loggedIn, setLoggedIn] = useState(false)
  const [running, setRunning] = useState(false)
  const [message, setMessage] = useState('')
  const [showSaveDialog, setShowSaveDialog] = useState(false)
  const [saveResult, setSaveResult] = useState<{ success: boolean; message: string } | null>(null)
  const [blacklist, setBlacklist] = useState<BlacklistItem[]>([])
  const [blacklistType, setBlacklistType] = useState<'company' | 'job'>('company')
  const [blacklistValue, setBlacklistValue] = useState('')

  const load = useCallback(async (refreshLogin = false) => {
    try {
      const [configRes, loginRes, statusRes, statsRes, listRes, blacklistRes] = await Promise.all([
        fetch(`${base}/config`), fetch(`${base}/login-status?refresh=${refreshLogin}`),
        fetch(`${base}/status`), fetch(`${base}/stats`), fetch(`${base}/list?page=1&size=50`), fetch(`${base}/blacklist`),
      ])
      if (configRes.ok) setConfig(await configRes.json())
      if (loginRes.ok) setLoggedIn((await loginRes.json()).isLoggedIn || false)
      if (statusRes.ok) setRunning((await statusRes.json()).isRunning || false)
      if (statsRes.ok) setStats(await statsRes.json())
      if (listRes.ok) setRows((await listRes.json()).items || [])
      if (blacklistRes.ok) setBlacklist(await blacklistRes.json())
    } catch { setMessage('后端暂时不可用，请检查服务是否已启动。') }
  }, [base])

  useEffect(() => { load(true) }, [load])
  useEffect(() => {
    const timer = setInterval(() => load(false), running ? 3000 : 15000)
    return () => clearInterval(timer)
  }, [load, running])

  const save = async () => {
    try {
      const res = await fetch(`${base}/config`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(config) })
      if (!res.ok) throw new Error('后端返回异常状态')
      const cookieRes = await fetch(`${base}/save-cookie`, { method: 'POST' })
      if (!cookieRes.ok) throw new Error('Cookie 保存失败')
      setMessage('配置已保存。')
      setSaveResult({ success: true, message: `${displayName}配置和当前登录信息已保存。` })
    } catch (error) {
      setMessage('配置保存失败。')
      setSaveResult({ success: false, message: `保存失败：${error instanceof Error ? error.message : '网络或服务异常'}` })
    } finally {
      setShowSaveDialog(true)
    }
  }
  const start = async () => {
    const saveRes = await fetch(`${base}/config`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(config) })
    if (!saveRes.ok) { setMessage('当前配置保存失败，未启动投递。'); return }
    const res = await fetch(`${base}/start`, { method: 'POST' })
    const data = await res.json()
    setMessage(data.message || '')
    if (res.ok) setRunning(true)
  }
  const stop = async () => {
    const res = await fetch(`${base}/stop`, { method: 'POST' })
    const data = await res.json()
    setMessage(data.message || '')
    setRunning(false)
  }
  const addBlacklist = async () => {
    const value = blacklistValue.trim()
    if (!value) return
    try {
      const res = await fetch(`${base}/blacklist`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ type: blacklistType, value }),
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) throw new Error(data.message || '添加黑名单失败')
      setBlacklistValue('')
      await load(false)
    } catch (error) {
      setSaveResult({ success: false, message: error instanceof Error ? error.message : '添加黑名单失败' })
      setShowSaveDialog(true)
    }
  }
  const deleteBlacklist = async (id: number) => {
    try {
      const res = await fetch(`${base}/blacklist/${id}`, { method: 'DELETE' })
      if (!res.ok) throw new Error('删除黑名单失败')
      setBlacklist(items => items.filter(item => item.id !== id))
    } catch (error) {
      setSaveResult({ success: false, message: error instanceof Error ? error.message : '删除黑名单失败' })
      setShowSaveDialog(true)
    }
  }

  return <div className="space-y-6">
    <PageHeader icon={<BiBriefcase className="text-2xl" />} title={`${displayName}配置`}
      subtitle={`配置${displayName}的实习岗位搜索、自动投递和结果分析`}
      iconClass="text-white" accentBgClass="bg-teal-500"
      actions={<div className="flex gap-2">
        <Button size="sm" onClick={() => load(true)} className="rounded-full"><BiRefresh className="mr-1" />刷新登录</Button>
        {running
          ? <Button size="sm" onClick={stop} className="rounded-full bg-red-500 text-white"><BiStop className="mr-1" />停止投递</Button>
          : <Button size="sm" onClick={start} disabled={!loggedIn} className="rounded-full bg-emerald-500 text-white"><BiPlay className="mr-1" />{loggedIn ? '开始投递' : `请先登录${displayName}`}</Button>}
        <Button size="sm" onClick={save} className="rounded-full bg-indigo-500 text-white"><BiSave className="mr-1" />保存配置</Button>
      </div>} />

    {message && <div className="rounded-lg border bg-white/70 px-4 py-3 text-sm dark:bg-neutral-900">{message}</div>}

    <Tabs defaultValue="config">
      <TabsList className="grid w-full grid-cols-2"><TabsTrigger value="config">平台配置</TabsTrigger><TabsTrigger value="analytics">投递分析</TabsTrigger></TabsList>
      <TabsContent value="config" className="mt-6 space-y-6">
        <Card><CardHeader><CardTitle>{displayName}平台说明</CardTitle></CardHeader><CardContent className="space-y-2 text-sm text-muted-foreground">
          <p>应用启动后会打开独立的 {displayName} 标签页，请先在该标签页手动登录，然后点击“刷新登录”。</p>
          <p>任务会处理每个关键词的全部搜索结果页，投递所有未投递且未命中黑名单的岗位；直到全部处理完成或你手动停止。</p>
        </CardContent></Card>
        <Card><CardHeader><CardTitle>配置参数</CardTitle></CardHeader><CardContent className="grid grid-cols-1 gap-4 md:grid-cols-3">
          <div className="space-y-2"><Label>搜索关键词（逗号分隔）</Label><Input value={config.keywords || ''} placeholder="计算机视觉实习, 算法实习"
            onChange={e => setConfig(v => ({ ...v, keywords: e.target.value }))} /></div>
          <div className="space-y-2"><Label>城市（留空表示不限）</Label><Input value={config.city || ''} placeholder="北京"
            onChange={e => setConfig(v => ({ ...v, city: e.target.value }))} /></div>
          {platform === 'shixiseng' && <>
            <div className="space-y-2"><Label>排序方式</Label><select className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              value={config.sortOrder || 'latest'} onChange={e => setConfig(v => ({ ...v, sortOrder: e.target.value as 'default' | 'latest' }))}>
              <option value="default">默认</option><option value="latest">最新</option>
            </select><p className="text-xs text-muted-foreground">搜索结果出现后再切换排序，避免搜索动作覆盖筛选。</p></div>
            <div className="space-y-2"><Label>附件简历序号</Label><Input type="number" min={1} value={config.resumeIndex || 1}
              onChange={e => setConfig(v => ({ ...v, resumeIndex: Number(e.target.value) || 1 }))} />
              <p className="text-xs text-muted-foreground">按弹窗中的附件简历顺序选择，默认第 1 份；不会选择在线简历。</p></div>
          </>}
        </CardContent></Card>
        <Card><CardHeader><CardTitle className="flex items-center gap-2"><BiTrash />共享黑名单管理（{blacklist.length} 条）</CardTitle></CardHeader>
          <CardContent className="space-y-4">
            <p className="text-sm text-muted-foreground">此名单与智联招聘及另一个校园平台实时共享。公司名称和岗位名称均使用关键词包含匹配，不区分英文大小写；命中后会在点击投递前跳过。</p>
            <div className="grid grid-cols-1 gap-2 md:grid-cols-[180px_1fr_auto]">
              <select className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm" value={blacklistType}
                onChange={e => setBlacklistType(e.target.value as 'company' | 'job')}>
                <option value="company">公司名称</option><option value="job">岗位名称</option>
              </select>
              <Input value={blacklistValue} onChange={e => setBlacklistValue(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') void addBlacklist() }}
                placeholder={blacklistType === 'company' ? '输入公司名称关键词' : '输入岗位名称关键词'} />
              <Button onClick={addBlacklist} disabled={!blacklistValue.trim()}><BiPlus className="mr-1" />添加</Button>
            </div>
            <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
              {(['company', 'job'] as const).map(type => {
                const items = blacklist.filter(item => item.type === type)
                return <div key={type} className="rounded-xl border p-3">
                  <div className="mb-3 text-sm font-medium">{type === 'company' ? '公司黑名单' : '岗位黑名单'}（{items.length}）</div>
                  {!items.length ? <p className="text-xs text-muted-foreground">暂无记录</p> : <div className="flex flex-wrap gap-2">
                    {items.map(item => <span key={item.id} className="inline-flex items-center gap-1 rounded-full bg-muted px-3 py-1 text-sm">
                      {item.value}<button type="button" onClick={() => deleteBlacklist(item.id)} className="ml-1 text-muted-foreground hover:text-red-500" aria-label={`删除 ${item.value}`}><BiTrash /></button>
                    </span>)}
                  </div>}
                </div>
              })}
            </div>
          </CardContent>
        </Card>
      </TabsContent>
      <TabsContent value="analytics" className="mt-6 space-y-6">
        <div className="grid grid-cols-2 gap-4 md:grid-cols-5">
          {([['今日投递', stats.todayDelivered], ['成功', stats.delivered], ['失败', stats.failed], ['待处理', stats.pending], ['全部岗位', stats.total]] as const).map(([label, value]) =>
            <Card key={label}><CardContent className="pt-6"><div className="flex items-center gap-2 text-sm text-muted-foreground"><BiBarChart />{label}</div><div className="mt-2 text-3xl font-semibold">{value || 0}</div></CardContent></Card>)}
        </div>
        <Card><CardHeader><CardTitle>最近岗位记录</CardTitle></CardHeader><CardContent className="overflow-x-auto">
          <table className="w-full min-w-[900px] text-sm"><thead><tr className="border-b text-left"><th className="p-2">岗位</th><th className="p-2">公司</th><th className="p-2">城市/薪资</th><th className="p-2">状态</th><th className="p-2">投递时间/原因</th></tr></thead>
            <tbody>{rows.map(row => <tr key={row.id} className="border-b align-top"><td className="p-2"><a className="text-blue-600 hover:underline" href={row.jobLink} target="_blank" rel="noreferrer">{row.jobTitle || row.jobId}</a></td><td className="p-2">{row.companyName || '-'}</td><td className="p-2">{row.location || '-'}<br />{row.salary || '-'}</td><td className="p-2">{row.deliveryStatus === 'delivered' ? '已投递' : row.deliveryStatus === 'failed' ? '失败' : '已发现'}</td><td className="p-2">{row.deliveredAt || row.failureReason || '-'}</td></tr>)}</tbody>
          </table>
          {!rows.length && <p className="py-8 text-center text-muted-foreground">还没有岗位记录。</p>}
        </CardContent></Card>
      </TabsContent>
    </Tabs>
    {showSaveDialog && saveResult && <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/40">
      <Card className="w-[92%] max-w-sm rounded-2xl border-0 bg-white shadow-2xl dark:bg-neutral-900">
        <CardHeader className="pb-2"><CardTitle className="flex items-center gap-2 text-lg">
          <BiSave className={saveResult.success ? 'text-green-500' : 'text-red-500'} />
          {saveResult.success ? '保存成功' : '保存失败'}
        </CardTitle></CardHeader>
        <CardContent><p className="mb-4 text-sm text-muted-foreground">{saveResult.message}</p>
          <div className="flex justify-end"><Button onClick={() => setShowSaveDialog(false)} className="rounded-full px-5">确定</Button></div>
        </CardContent>
      </Card>
    </div>}
  </div>
}
