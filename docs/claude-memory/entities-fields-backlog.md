---
name: entities-fields-backlog
description: "Задел «сущности с полями» — три этажа, поля только в api-source, детали в meta.md репо"
metadata: 
  node_type: memory
  type: project
  originSessionId: 196faca2-ed5e-40fa-b6fc-c483fcf1aaee
  modified: 2026-08-19T17:53:40.934Z
---

Отложенная фича arch-analyzer (решено 2026-08-19, не начато): поля передаваемых сущностей.

**Правило трёх этажей:** модель — только имена типов (узлов-полей не бывает);
поля — фактами в `tools/api-source` (доработка lst: резолв DTO по typed-класспасу);
поверх json — field-level impact, PII-автотег, дифф контракта между прогонами.

**Триггер:** заболит PII-трекинг или страх ломающих изменений. Пока не болит — не делать.

Полное описание — `meta.md`, раздел «Задел: сущности с полями» в репо движка. См. [[arch-analyzer-project]].
