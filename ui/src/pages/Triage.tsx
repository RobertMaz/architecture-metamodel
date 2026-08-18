import { useState } from "react"
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query"
import { Link } from "react-router"
import { Combine, Globe, Sparkles } from "lucide-react"
import { toast } from "sonner"

import { api, type UnresolvedEntry } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"

function useResolve() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ stubId, body }: { stubId: string; body: Parameters<typeof api.resolve>[1] }) =>
      api.resolve(stubId, body),
    onSuccess: (_, v) => {
      toast.success(`«${v.stubId}» разрешён, модель перегенерирована`)
      // Точечно: рефетч гипотез решённого stub'а дал бы 404.
      for (const key of ["unresolved", "containers", "diff", "report"]) {
        qc.invalidateQueries({ queryKey: [key] })
      }
    },
    onError: (e) => toast.error(String(e)),
  })
}

function ExternalDialog({ stubId }: { stubId: string }) {
  const resolve = useResolve()
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState({ id: "", title: "", contract: "" })
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button size="sm" variant="outline">
          <Globe /> Это external
        </Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Внешняя система</DialogTitle>
          <DialogDescription>Чёрный ящик вне компании: один узел + договор.</DialogDescription>
        </DialogHeader>
        <div className="grid gap-3">
          <div className="grid gap-1.5">
            <Label htmlFor="e-id">id (например, stripe)</Label>
            <Input id="e-id" value={form.id} onChange={(e) => setForm((f) => ({ ...f, id: e.target.value }))} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="e-title">Название</Label>
            <Input id="e-title" value={form.title} onChange={(e) => setForm((f) => ({ ...f, title: e.target.value }))} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="e-contract">Договор/SLA (необязательно)</Label>
            <Input
              id="e-contract"
              value={form.contract}
              onChange={(e) => setForm((f) => ({ ...f, contract: e.target.value }))}
            />
          </div>
        </div>
        <DialogFooter>
          <Button
            disabled={!form.id || resolve.isPending}
            onClick={() =>
              resolve.mutate(
                {
                  stubId,
                  body: {
                    external: { id: form.id, title: form.title || undefined, contract: form.contract || undefined },
                  },
                },
                { onSuccess: () => setOpen(false) },
              )
            }
          >
            Зафиксировать
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function StubCard({ entry }: { entry: UnresolvedEntry }) {
  const resolve = useResolve()
  const containers = useQuery({ queryKey: ["containers"], queryFn: api.containers })
  const [manual, setManual] = useState("")
  const [askHypotheses, setAskHypotheses] = useState(false)

  const stubId = entry.stubId

  const hypotheses = useQuery({
    queryKey: ["hypotheses", stubId],
    queryFn: () => api.hypotheses(stubId!),
    enabled: askHypotheses && !!stubId,
  })

  return (
    <Card>
      <CardHeader>
        <CardTitle className="font-mono text-base">{stubId ?? "(без stub-узла)"}</CardTitle>
        <CardDescription>
          {entry.note && <span>{entry.note}. </span>}
          {entry.signature && (
            <>
              {entry.signature.hosts.length > 0 && <>hosts: {entry.signature.hosts.join(", ")}; </>}
              {entry.signature.feignNames.length > 0 && <>feign: {entry.signature.feignNames.join(", ")}; </>}
              {entry.signature.urlTemplates.length > 0 && <>url: {entry.signature.urlTemplates.join(", ")}</>}
            </>
          )}
        </CardDescription>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {entry.observedEndpoints.length > 0 && (
          <div>
            Наблюдённые эндпоинты:{" "}
            {entry.observedEndpoints.map((e) => (
              <Badge key={`${e.method}-${e.path}`} className="mr-1 font-mono" variant="outline">
                {e.method} {e.path}
              </Badge>
            ))}
          </div>
        )}
        <div>
          Кто зовёт:{" "}
          {entry.callers.map((c) => (
            <Link key={`${c.container}-${c.source}`} className="mr-2 font-mono text-xs hover:underline" to={`/containers/${c.container}`}>
              {c.container}
            </Link>
          ))}
        </div>

        {stubId && (
          <div className="flex flex-wrap items-center gap-2 border-t pt-3">
            {entry.candidates.map((c) => (
              <Button
                key={c.container}
                size="sm"
                disabled={resolve.isPending}
                onClick={() => resolve.mutate({ stubId, body: { container: c.container } })}
                title={`совпало: ${c.matched}`}
              >
                <Combine /> Склеить с {c.container} ({Math.round(c.score * 100)}%)
              </Button>
            ))}
            <div className="flex items-center gap-2">
              <Select value={manual} onValueChange={setManual}>
                <SelectTrigger className="w-64">
                  <SelectValue placeholder="Другой контейнер…" />
                </SelectTrigger>
                <SelectContent>
                  {(containers.data ?? []).map((c) => (
                    <SelectItem key={c.id} value={c.id}>
                      {c.id}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
              <Button
                size="sm"
                variant="secondary"
                disabled={!manual || resolve.isPending}
                onClick={() => resolve.mutate({ stubId, body: { container: manual } })}
              >
                Склеить
              </Button>
            </div>
            <ExternalDialog stubId={stubId} />
            <Button size="sm" variant="ghost" onClick={() => setAskHypotheses(true)} disabled={hypotheses.isFetching}>
              <Sparkles /> Гипотезы LLM
            </Button>
          </div>
        )}

        {askHypotheses && hypotheses.data && (
          <div className="space-y-1 border-t pt-3">
            {!hypotheses.data.configured && (
              <span className="text-muted-foreground">LLM не настроен — заполни registry/llm.yml.</span>
            )}
            {hypotheses.data.configured && !hypotheses.data.hypotheses.length && (
              <span className="text-muted-foreground">Гипотез нет.</span>
            )}
            {hypotheses.data.hypotheses.map((h) => (
              <div key={h.name} className="flex items-center gap-2">
                <Badge variant="secondary">{Math.round(h.confidence * 100)}%</Badge>
                <span>{h.name}</span>
                {h.container && (
                  <Button size="sm" variant="link" onClick={() => setManual(h.container!)}>
                    {h.container}
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

export function Triage() {
  const unresolved = useQuery({ queryKey: ["unresolved"], queryFn: api.unresolved })
  const list = unresolved.data?.unresolved ?? []

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">Триаж нераспознанного</h1>
        <p className="text-sm text-muted-foreground">
          Цели вызовов, чьё имя не установлено. Склейка перегенерирует модель: рёбра переезжают из stub'а в реальный
          сервис, дифф остаётся в git.
        </p>
      </div>
      {unresolved.isLoading && <div className="text-muted-foreground">Загрузка…</div>}
      {!unresolved.isLoading && !list.length && (
        <Card>
          <CardContent className="py-8 text-center text-muted-foreground">
            Пусто — всё распознано. Так и должно заканчиваться.
          </CardContent>
        </Card>
      )}
      {list.map((e, i) => (
        <StubCard key={e.stubId ?? i} entry={e} />
      ))}
    </div>
  )
}
