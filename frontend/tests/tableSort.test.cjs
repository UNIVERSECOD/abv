const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const vm = require('node:vm')
const exportsObject = {}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/utils/tableSort.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, { exports: exportsObject })
const { sortTableRows } = exportsObject
const ids = rows => rows.map(row => row.id).join(',')

test('newest creation wins regardless of leave start date and equal timestamps use id', () => {
  const rows = [
    { id: 1, createdAt: '2026-10-25T10:00:00', startDate: '2027-01-01' },
    { id: 2, createdAt: '2026-10-25T11:00:00', startDate: '2026-01-01' },
    { id: 3, createdAt: '2026-10-25T11:00:00', startDate: '2026-02-01' },
  ]
  assert.equal(ids(sortTableRows(rows, null, () => null)), '3,2,1')
  assert.equal(ids(rows), '1,2,3')
})

test('legacy rows without creation timestamps fall back to newest id', () => {
  assert.equal(ids(sortTableRows([{ id: 2 }, { id: 9 }, { id: 3 }], null, () => null)), '9,3,2')
})

test('known creation dates sort consistently before legacy missing or invalid dates', () => {
  const rows = [{ id: 3, createdAt: '2026-10-20' }, { id: 1, createdAt: '2026-10-25' }, { id: 2 }, { id: 4, createdAt: 'invalid' }]
  assert.equal(ids(sortTableRows(rows, null, () => null)), '1,3,4,2')
})

test('numeric sorting handles multi-digit day totals and keeps missing values last both ways', () => {
  const rows = [{ id: 1, days: 2 }, { id: 2, days: 10 }, { id: 3, days: null }, { id: 4, days: 0 }]
  assert.equal(ids(sortTableRows(rows, { key: 'days', direction: 'asc' }, row => row.days)), '4,1,2,3')
  assert.equal(ids(sortTableRows(rows, { key: 'days', direction: 'desc' }, row => row.days)), '2,1,4,3')
})

test('Azerbaijani names sort by alphabet with natural numeric codes', () => {
  const rows = [{ id: 1, name: 'Şəms' }, { id: 2, name: 'Əli' }, { id: 3, name: 'Cavid' }]
  assert.equal(ids(sortTableRows(rows, { key: 'name', direction: 'asc' }, row => row.name)), '3,2,1')
  assert.equal(ids(sortTableRows([{ id: 1, code: 'HR10' }, { id: 2, code: 'HR2' }],
    { key: 'code', direction: 'asc' }, row => row.code)), '2,1')
})

test('No sorts stable default ordinals and text ties keep newest record first', () => {
  const rows = [{ id: 8, label: 'Eyni' }, { id: 3, label: 'Eyni' }]
  const ordinals = new Map([[8, 1], [3, 2]])
  assert.equal(ids(sortTableRows(rows, { key: 'no', direction: 'desc' }, row => ordinals.get(row.id))), '3,8')
  assert.equal(ids(sortTableRows(rows, { key: 'label', direction: 'desc' }, row => row.label)), '8,3')
})
