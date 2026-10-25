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
const { allocateLeaveDebitDays, leaveMonthRange, leaveOverlapsPeriod, visibleLeaveTypes, leaveCalendarDays } = exportsObject

const debitRequest = (id, startDate, endDate, overrides = {}) => ({
  id, employeeId: 1, leaveTypeId: 1, status: 'APPROVED', startDate, endDate, ...overrides,
})
const openingDates = new Map([[1, '2026-10-20'], [2, '2026-10-20']])

test('migrated charged rows preserve exact stored debit regardless of current type or opening date', () => {
  const result = allocateLeaveDebitDays([
    debitRequest(1, '2026-10-01', '2026-10-05', { legacyBalanceAccounting: true, balanceChargedDays: 3, leaveTypeId: 9 }),
    debitRequest(2, '2026-10-20', '2026-10-24', { legacyBalanceAccounting: true, balanceChargedDays: 0 }),
    debitRequest(3, '2026-10-20', '2026-10-24', { legacyBalanceAccounting: true, balanceChargedDays: 5, status: 'REJECTED' }),
    debitRequest(4, '2026-10-20', '2026-10-24', { legacyBalanceAccounting: true, balanceChargedDays: 5, status: 'PENDING' }),
    debitRequest(5, '2026-10-20', '2026-10-24'),
  ], new Set([1]), openingDates)
  assert.equal(result.get(1), 3)
  assert.equal(result.get(2), 0)
  assert.equal(result.get(3), 0)
  assert.equal(result.get(4), 0)
  assert.equal(result.get(5), 5)
})

test('editing a migrated row switches its debit to the current type policy', () => {
  const legacy = debitRequest(1, '2026-10-20', '2026-10-24', { legacyBalanceAccounting: true, balanceChargedDays: 2 })
  assert.equal(allocateLeaveDebitDays([legacy], new Set([1]), openingDates).get(1), 2)
  const edited = { ...legacy, legacyBalanceAccounting: false }
  assert.equal(allocateLeaveDebitDays([edited], new Set([1]), openingDates).get(1), 5)
  assert.equal(allocateLeaveDebitDays([{ ...edited, leaveTypeId: 9 }], new Set([1]), openingDates).get(1), 0)
})

test('overlapping legacy rows allocate only unique debit days in stable id order', () => {
  const rows = [debitRequest(2, '2026-10-22', '2026-10-26'), debitRequest(1, '2026-10-20', '2026-10-24')]
  const snapshot = JSON.stringify(rows)
  const result = allocateLeaveDebitDays(rows, new Set([1]), openingDates)
  assert.equal(result.get(1), 5)
  assert.equal(result.get(2), 2)
  assert.equal([...result.values()].reduce((sum, days) => sum + days, 0), 7)
  assert.equal(JSON.stringify(rows), snapshot)
})

test('nested and duplicate requests do not debit again while later extensions add only new days', () => {
  const rows = [debitRequest(1, '2026-10-20', '2026-10-30'), debitRequest(2, '2026-10-22', '2026-10-24'),
    debitRequest(3, '2026-10-20', '2026-10-30'), debitRequest(4, '2026-10-29', '2026-11-02')]
  const result = allocateLeaveDebitDays(rows, new Set([1]), openingDates)
  assert.equal(result.get(1), 11)
  assert.equal(result.get(2), 0)
  assert.equal(result.get(3), 0)
  assert.equal(result.get(4), 3)
})

test('different debit types share the same union but employees keep independent allocations', () => {
  const result = allocateLeaveDebitDays([
    debitRequest(1, '2026-10-20', '2026-10-24'),
    debitRequest(2, '2026-10-22', '2026-10-26', { leaveTypeId: 2 }),
    debitRequest(3, '2026-10-22', '2026-10-26', { employeeId: 2, leaveTypeId: 2 }),
  ], new Set([1, 2]), openingDates)
  assert.equal(result.get(1), 5)
  assert.equal(result.get(2), 2)
  assert.equal(result.get(3), 5)
})

test('opening dates clip historical portions before overlaps are allocated', () => {
  const result = allocateLeaveDebitDays([
    debitRequest(1, '2026-10-18', '2026-10-22'), debitRequest(2, '2026-10-21', '2026-10-25'),
    debitRequest(3, '2026-10-01', '2026-10-19'),
  ], new Set([1]), openingDates)
  assert.equal(result.get(1), 3)
  assert.equal(result.get(2), 3)
  assert.equal(result.get(3), 0)
})

test('pending, rejected and non-debit requests neither charge nor consume another row allocation', () => {
  const result = allocateLeaveDebitDays([
    debitRequest(1, '2026-10-20', '2026-10-24', { status: 'PENDING' }),
    debitRequest(2, '2026-10-20', '2026-10-24', { status: 'REJECTED' }),
    debitRequest(3, '2026-10-20', '2026-10-24', { leaveTypeId: 9 }),
    debitRequest(4, '2026-10-20', '2026-10-24'),
  ], new Set([1]), openingDates)
  assert.equal(result.get(1), 0)
  assert.equal(result.get(2), 0)
  assert.equal(result.get(3), 0)
  assert.equal(result.get(4), 5)
})

test('cancellation and employee or type reassignment recalculate row contributions', () => {
  const first = debitRequest(1, '2026-10-20', '2026-10-24')
  const second = debitRequest(2, '2026-10-22', '2026-10-26')
  assert.equal(allocateLeaveDebitDays([first, second], new Set([1]), openingDates).get(2), 2)
  for (const change of [{ status: 'REJECTED' }, { employeeId: 2 }, { leaveTypeId: 9 }]) {
    const result = allocateLeaveDebitDays([{ ...first, ...change }, second], new Set([1]), openingDates)
    assert.equal(result.get(2), 5)
    assert.equal(result.get(1), change.employeeId ? 5 : 0)
  }
})

test('display filtering and sorting reuse allocations from the full request set', () => {
  const rows = [debitRequest(1, '2026-10-20', '2026-10-24'), debitRequest(2, '2026-10-22', '2026-10-26')]
  const result = allocateLeaveDebitDays(rows, new Set([1]), openingDates)
  const displayed = rows.filter(row => row.id === 2)
  assert.equal(displayed.reduce((sum, row) => sum + result.get(row.id), 0), 2)
  assert.equal(allocateLeaveDebitDays([...rows].reverse(), new Set([1]), openingDates).get(2), 2)
})

test('long ranges are represented as intervals instead of individual calendar days', () => {
  const rows = [debitRequest(1, '1000-01-01', '9000-12-31'), debitRequest(2, '2000-01-01', '8000-12-31')]
  const result = allocateLeaveDebitDays(rows, new Set([1]), new Map([[1, '1000-01-01']]))
  assert.equal(result.get(1), leaveCalendarDays('1000-01-01', '9000-12-31'))
  assert.equal(result.get(2), 0)
})

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
