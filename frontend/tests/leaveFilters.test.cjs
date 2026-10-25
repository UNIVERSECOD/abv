const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const vm = require('node:vm')
const exportsObject = {}
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/utils/leaveFilters.ts'), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS },
}).outputText, { exports: exportsObject })
const { leaveMonthRange, leaveOverlapsPeriod, visibleLeaveTypes, leaveCalendarDays } = exportsObject

test('each leave row counts its own inclusive calendar days across month boundaries', () => {
  assert.equal(leaveCalendarDays('2026-10-25', '2026-10-28'), 4)
  assert.equal(leaveCalendarDays('2024-02-28', '2024-03-01'), 3)
  assert.equal(leaveCalendarDays('2026-10-25', '2026-10-25'), 1)
  assert.equal(leaveCalendarDays('2026-10-25', '2026-10-24'), 0)
})

test('month covers leap February and December without timezone drift', () => {
  assert.equal(leaveMonthRange('2024-02').through, '2024-02-29')
  assert.equal(leaveMonthRange('2026-02').through, '2026-02-28')
  assert.equal(leaveMonthRange('2026-12').from, '2026-12-01')
  assert.equal(leaveMonthRange('2026-12').through, '2026-12-31')
  assert.equal(leaveMonthRange('').through, '')
})

test('month filter includes spanning leave and both inclusive boundaries', () => {
  const { from, through } = leaveMonthRange('2026-10')
  assert.equal(leaveOverlapsPeriod('2026-09-20', '2026-11-02', from, through), true)
  assert.equal(leaveOverlapsPeriod('2026-09-20', '2026-10-01', from, through), true)
  assert.equal(leaveOverlapsPeriod('2026-10-31', '2026-11-02', from, through), true)
  assert.equal(leaveOverlapsPeriod('2026-09-20', '2026-09-30', from, through), false)
  assert.equal(leaveOverlapsPeriod('2026-11-01', '2026-11-02', from, through), false)
})

test('date filters support one day, open bounds and invalid ranges', () => {
  assert.equal(leaveOverlapsPeriod('2026-10-10', '2026-10-12', '2026-10-12', '2026-10-12'), true)
  assert.equal(leaveOverlapsPeriod('2026-10-10', '2026-10-12', '2026-10-13', ''), false)
  assert.equal(leaveOverlapsPeriod('2026-10-10', '2026-10-12', '', '2026-10-09'), false)
  assert.equal(leaveOverlapsPeriod('2026-10-10', '2026-10-12', '', ''), true)
  assert.equal(leaveOverlapsPeriod('2026-10-10', '2026-10-12', '2026-10-15', '2026-10-01'), false)
})

test('deleted types disappear without records and remain with historical data', () => {
  const types = [{ id: 1, archived: false }, { id: 2, archived: true }, { id: 3, archived: true }]
  assert.equal(visibleLeaveTypes(types, [{ leaveTypeId: 3 }]).map(type => type.id).join(','), '1,3')
  assert.equal(visibleLeaveTypes(types, []).map(type => type.id).join(','), '1')
})
