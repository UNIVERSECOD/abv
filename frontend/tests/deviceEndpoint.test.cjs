const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const ts = require('typescript')
const vm = require('node:vm')
const source = fs.readFileSync(require('node:path').join(__dirname, '../src/utils/deviceEndpoint.ts'), 'utf8')
const exportsObject = {}
vm.runInNewContext(ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, { exports: exportsObject })
const { deviceEndpoint, splitDeviceEndpoint } = exportsObject

test('preserves stored IPv4 port and lets editing replace it', () => {
  assert.equal(deviceEndpoint('192.168.1.10:8080'), '192.168.1.10:8080')
  assert.equal(deviceEndpoint('192.168.1.10:8080', 8000), '192.168.1.10:8000')
  assert.equal(splitDeviceEndpoint('192.168.1.10:8080', 80).port, 8080)
})
test('adds brackets and the selected port to IPv6 without duplicating either', () => {
  assert.equal(deviceEndpoint('fd00::10', 8080), '[fd00::10]:8080')
  assert.equal(deviceEndpoint('[fd00::10]:8000', 8080), '[fd00::10]:8080')
  assert.equal(splitDeviceEndpoint('[fd00::10]:8000').host, 'fd00::10')
})
