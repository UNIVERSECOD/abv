const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { test } = require('node:test')
const vm = require('node:vm')
const ts = require('typescript')

function loadStore(getAll) {
  const source = fs.readFileSync(path.join(__dirname, '../src/store/employeeStore.ts'), 'utf8')
  const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } })
  const exports = {}
  vm.runInNewContext(compiled.outputText, {
    exports,
    require: name => name === '../api/employeeApi.ts' ? { employeeApi: { getAll } } : require(name),
  })
  return exports.useEmployeeStore
}

const response = (content, currentPage, totalPages, totalElements = content.length) =>
  ({ data: { content, currentPage, totalPages, totalElements } })

test('an older filter response cannot overwrite the latest employee page', async () => {
  const pending = []
  const store = loadStore((page, size, filters) => new Promise(resolve => pending.push({ filters, resolve })))
  const older = store.getState().fetchEmployees(0, 20, { q: 'Old' })
  const newer = store.getState().fetchEmployees(0, 20, { q: 'New' })
  pending[1].resolve(response([{ id: 2 }], 0, 1))
  await newer
  pending[0].resolve(response([{ id: 1 }], 0, 1))
  await older
  assert.equal(store.getState().filters.q, 'New')
  assert.equal(store.getState().employees[0].id, 2)
})

test('a disappearing employee page falls back to the last page with its filters', async () => {
  const calls = []
  const store = loadStore(async (page, size, filters) => {
    calls.push([page, size, filters.q])
    return page === 2 ? response([], 2, 2, 21) : response([{ id: 21 }], 1, 2, 21)
  })
  assert.equal(await store.getState().fetchEmployees(2, 20, { q: 'Pager' }), true)
  assert.deepEqual(calls, [[2, 20, 'Pager'], [1, 20, 'Pager']])
  assert.equal(store.getState().currentPage, 1)
})
