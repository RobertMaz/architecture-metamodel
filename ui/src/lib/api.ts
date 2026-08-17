/** Типизированный клиент REST-API анализатора (Ktor, /api). */

export type SystemInfo = {
  id: string
  title: string
  kind: "system" | "orgSystem" | "externalSystem"
  description?: string | null
}

export type ContainerInfo = {
  id: string
  system: string
  repo: string
  path: string
  analyzed: boolean
  state: "idle" | "running" | "done" | "failed"
  lanes: string[]
  operations: number
  calls: number
  stores: number
  subscribes: number
  publishes: number
  unresolvedCalls: number
}

export type Operation = {
  method: string
  path: string
  summary?: string
  params?: string
  request?: string
  response?: string
  deprecated?: boolean
  source: string
  confidence: number
}

export type CallTarget = Record<string, string>

export type ContainerReport = {
  doc: {
    container: string
    source: { repo: string; commit: string; extractedAt: string; extractor: string }
    containerInfo: { kind: string; title: string; technology: string; appName?: string }
    api?: { basePath: string; title: string } | null
    operations: Operation[]
    publishes: { channel: string; schema?: string; source: string; confidence: number }[]
    subscribes: { channel: string; group?: string; payload?: string; source: string; confidence: number }[]
    calls: { method?: string; path?: string; target: CallTarget; source: string; confidence: number }[]
    stores: { kind: string; address: string; technology?: string; access: string; entities?: string; source: string; confidence: number }[]
  }
  report: { conflicts: string[]; lowConfidence: string[]; unresolvedCalls: number } | null
}

export type ModelDiff = {
  files: { path: string; status: "new" | "modified" | "deleted" }[]
  patch: string
}

async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const rs = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...init,
  })
  if (!rs.ok) {
    const body = await rs.json().catch(() => null)
    throw new Error(body?.error ?? `${rs.status} ${rs.statusText}`)
  }
  return rs.json() as Promise<T>
}

export type UnresolvedEntry = {
  stubId: string | null
  note?: string
  signature?: { feignNames: string[]; hosts: string[]; urlTemplates: string[] }
  observedEndpoints: { method: string; path: string }[]
  callers: { container: string; source: string }[]
  candidates: { container: string; score: number; matched: string }[]
}

export const api = {
  systems: () => http<SystemInfo[]>("/api/systems"),
  containers: () => http<ContainerInfo[]>("/api/containers"),
  report: (id: string) => http<ContainerReport>(`/api/containers/${id}/report`),
  diff: () => http<ModelDiff>("/api/diff"),
  analyze: (id: string) => http<{ started: boolean }>(`/api/containers/${id}/analyze`, { method: "POST" }),
  addSystem: (body: { id: string; title: string; kind: string; description?: string; owner: string }) =>
    http<{ created: string }>("/api/systems", { method: "POST", body: JSON.stringify(body) }),
  addContainer: (body: { id: string; repo: string; path: string; jar?: string }) =>
    http<{ created: string }>("/api/containers", { method: "POST", body: JSON.stringify(body) }),
  unresolved: () => http<{ unresolved: UnresolvedEntry[] }>("/api/unresolved"),
  resolve: (stubId: string, body: { container?: string; external?: { id: string; title?: string; contract?: string } }) =>
    http<{ resolved: string }>(`/api/unresolved/${stubId}/resolve`, { method: "POST", body: JSON.stringify(body) }),
  hypotheses: (stubId: string) =>
    http<{ configured: boolean; hypotheses: { name: string; container?: string | null; confidence: number }[] }>(
      `/api/unresolved/${stubId}/hypotheses`,
    ),
}
