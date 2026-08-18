import type { ContainerReport } from "@/lib/api"
import { Badge } from "@/components/ui/badge"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"

function Confidence({ value }: { value: number }) {
  return <Badge variant={value >= 0.8 ? "outline" : "secondary"}>{value.toFixed(2)}</Badge>
}

export function ReportView({ report }: { report: ContainerReport }) {
  const { doc, report: rec } = report

  return (
    <div className="space-y-4">
      <div className="grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>Контейнер</CardTitle>
            <CardDescription>
              {doc.containerInfo.kind} · {doc.containerInfo.technology} · commit {doc.source.commit} ·{" "}
              {doc.source.extractedAt}
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            <div>
              Эндпоинтов: {doc.operations.length}
              {doc.api ? ` (base ${doc.api.basePath})` : ""} · вызовов: {doc.calls.length} · сторов:{" "}
              {doc.stores.length} · подписок: {doc.subscribes.length} · публикаций: {doc.publishes.length}
            </div>
            {rec && rec.conflicts.length > 0 && (
              <div className="text-destructive">Конфликты: {rec.conflicts.join("; ")}</div>
            )}
            {rec && rec.lowConfidence.length > 0 && (
              <div className="text-muted-foreground">Посмотреть глазами: {rec.lowConfidence.join("; ")}</div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Сторы и каналы</CardTitle>
          </CardHeader>
          <CardContent className="space-y-1 text-sm">
            {doc.stores.map((s) => (
              <div key={`${s.kind}-${s.address}`}>
                <Badge variant="outline">{s.access}</Badge> {s.technology ?? s.kind}{" "}
                <span className="font-mono text-xs">{s.address || "(адрес из конфига недоступен)"}</span>
                {s.entities && <span className="text-muted-foreground"> · {s.entities}</span>}
              </div>
            ))}
            {doc.publishes.map((p) => (
              <div key={`pub-${p.channel}`}>
                <Badge variant="outline">publish</Badge> {p.channel}
                {p.schema && <span className="text-muted-foreground"> · {p.schema}</span>}
              </div>
            ))}
            {doc.subscribes.map((s) => (
              <div key={`sub-${s.channel}`}>
                <Badge variant="outline">deliver</Badge> {s.channel}
                {s.group && <span className="text-muted-foreground"> · group {s.group}</span>}
              </div>
            ))}
            {!doc.stores.length && !doc.publishes.length && !doc.subscribes.length && (
              <div className="text-muted-foreground">Пусто</div>
            )}
          </CardContent>
        </Card>
      </div>

      {doc.operations.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Эндпоинты</CardTitle>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Метод</TableHead>
                  <TableHead>Путь</TableHead>
                  <TableHead>Домен</TableHead>
                  <TableHead>Параметры</TableHead>
                  <TableHead>Ответ</TableHead>
                  <TableHead>Источник</TableHead>
                  <TableHead>conf</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {doc.operations.map((op) => (
                  <TableRow key={`${op.method}-${op.path}`}>
                    <TableCell>
                      <Badge variant={op.deprecated ? "destructive" : "default"}>{op.method}</Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{op.path}</TableCell>
                    <TableCell>{op.group ? <Badge variant="outline">{op.group}</Badge> : ""}</TableCell>
                    <TableCell className="font-mono text-xs">{op.params ?? ""}</TableCell>
                    <TableCell className="font-mono text-xs">{op.response ?? ""}</TableCell>
                    <TableCell className="max-w-64 truncate font-mono text-xs" title={op.source}>
                      {op.source}
                    </TableCell>
                    <TableCell>
                      <Confidence value={op.confidence} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      {doc.calls.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Исходящие вызовы</CardTitle>
            <CardDescription>Цели без container разрешит реестр (подпроект 3).</CardDescription>
          </CardHeader>
          <CardContent>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Метод</TableHead>
                  <TableHead>Путь</TableHead>
                  <TableHead>Цель</TableHead>
                  <TableHead>conf</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {doc.calls.map((c, i) => (
                  <TableRow key={i}>
                    <TableCell>{c.method ?? "?"}</TableCell>
                    <TableCell className="font-mono text-xs">{c.path ?? c.target.urlTemplate ?? ""}</TableCell>
                    <TableCell className="font-mono text-xs">
                      {c.target.container ?? c.target.feignName ?? c.target.host ?? c.target.urlTemplate ?? "?"}
                      {!c.target.container && (
                        <Badge className="ml-2" variant="secondary">
                          не разрешён
                        </Badge>
                      )}
                    </TableCell>
                    <TableCell>
                      <Confidence value={c.confidence} />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
