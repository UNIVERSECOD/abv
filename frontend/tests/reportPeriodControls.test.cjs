const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ts = require('typescript')
const vm = require('node:vm')
const React = require('react')
const { renderToStaticMarkup } = require('react-dom/server')

function renderReport(view, start, end) {
  const filters = { view, start, end }
  function load(file) {
    const exports = {}
    const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
    }).outputText
    vm.runInNewContext(code, { exports, require: name => {
      if (name === 'react' || name === 'react/jsx-runtime') return require(name)
      if (name === 'react-router-dom') return { useSearchParams: () => [new URLSearchParams()] }
      if (name.includes('/components/Layout')) return { default: ({ children }) => children }
      if (name.includes('/store/attendanceReportStore')) return {
        reportDateError: () => null,
        useAttendanceReportStore: () => ({ rows: [], filters, page: 0, totalPages: 0, totalElements: 0 }),
      }
      if (name.includes('/api/') || name.includes('/store/toastStore')) return {}
      return load(path.resolve(path.dirname(file), name))
    } })
    return exports
  }
  const Page = load(path.join(__dirname, '../src/pages/ReportsPage.tsx')).default
  return renderToStaticMarkup(React.createElement(Page))
}

for (const view of ['DAILY', 'DETAILS']) {
  test(`${view} keeps day inputs and existing quick ranges`, () => {
    const html = renderReport(view, '2026-10-13', '2026-10-13')
    for (const label of ['Bu gün', 'Dünən', 'Bu ay', 'Bu il']) assert.ok(html.includes(label))
    assert.ok(html.includes('id="report-start"'))
    assert.ok(html.includes('id="report-end"'))
    assert.ok(!html.includes('id="report-month"'))
    assert.ok(!html.includes('id="report-year"'))
  })
}

for (const view of ['MONTHLY', 'YEARLY']) {
  test(`${view} shows period controls without day shortcuts or partial-date fields`, () => {
    const html = renderReport(view, '2024-02-01', '2024-02-29')
    for (const label of ['Bu gün', 'Dünən', 'Bu ay', 'Bu il']) assert.ok(!html.includes(label))
    assert.ok(!html.includes('id="report-start"'))
    assert.ok(!html.includes('id="report-end"'))
    assert.ok(html.includes('id="report-year"'))
    assert.ok(html.includes('value="2024"'))
    assert.equal(html.includes('id="report-month"'), view === 'MONTHLY')
    if (view === 'MONTHLY') {
      assert.ok(html.includes('value="02" selected="">Fevral'))
      assert.ok(html.includes('Dekabr'))
    }
  })
}
