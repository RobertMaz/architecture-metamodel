import { useEffect, useState } from "react"
import { useMutation, useQueryClient } from "@tanstack/react-query"
import { Save } from "lucide-react"
import { toast } from "sonner"

import { api, type ContainerInfo } from "@/lib/api"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Input } from "@/components/ui/input"
import { Label } from "@/components/ui/label"

/**
 * Источники правды контейнера — редактируются в любой момент после онбординга:
 * дописал JAR или URL живой апки → «Сохранить» → «Анализ» дообогатит модель.
 */
export function SourcesCard({ container }: { container: ContainerInfo }) {
  const qc = useQueryClient()
  const [form, setForm] = useState({ repo: "", path: "", jar: "", runtimeUrl: "", traces: "", openapi: "" })

  useEffect(() => {
    setForm({
      repo: container.repo ?? "",
      path: container.path ?? "",
      jar: container.jar ?? "",
      runtimeUrl: container.runtimeUrl ?? "",
      traces: container.traces ?? "",
      openapi: container.openapi ?? "",
    })
  }, [container.id, container.repo, container.path, container.jar, container.runtimeUrl, container.traces, container.openapi])

  const save = useMutation({
    mutationFn: () => api.updateSources(container.id, form),
    onSuccess: () => {
      toast.success("Источники сохранены — запускай анализ для дообогащения")
      qc.invalidateQueries({ queryKey: ["containers"] })
    },
    onError: (e) => toast.error(e instanceof Error ? e.message : String(e)),
  })

  const set = (k: keyof typeof form) => (v: string) => setForm((f) => ({ ...f, [k]: v }))

  const fields: { key: keyof typeof form; label: string }[] = [
    { key: "repo", label: "URL репозитория" },
    { key: "path", label: "Локальный путь к сорцам (обязателен)" },
    { key: "jar", label: "JAR из Nexus/сборки — полка bytecode" },
    { key: "runtimeUrl", label: "URL запущенной апки — полка runtime (Actuator)" },
    { key: "traces", label: "Файл OTel-спанов — полка traces" },
    { key: "openapi", label: "OpenAPI-спека — полка openapi (иначе автопоиск openapi.yml)" },
  ]

  return (
    <Card>
      <CardHeader>
        <CardTitle>Источники</CardTitle>
        <CardDescription>Пустое поле удаляет источник. После сохранения — «Анализ».</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-3 md:grid-cols-2">
        {fields.map((f) => (
          <div key={f.key} className="grid gap-1.5">
            <Label htmlFor={`src-${f.key}`}>{f.label}</Label>
            <Input id={`src-${f.key}`} value={form[f.key]} onChange={(e) => set(f.key)(e.target.value)} />
          </div>
        ))}
        <div className="flex items-end">
          <Button disabled={save.isPending || !form.path} onClick={() => save.mutate()}>
            <Save /> Сохранить
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
