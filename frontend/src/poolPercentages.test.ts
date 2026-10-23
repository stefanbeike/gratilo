import { expect, it } from 'vitest'
import { percentageBasisPoints } from './poolPercentages'

it('parses percentage-point weights as exact integer basis points', () => {
  for (const [input, expected] of [['50',5000],['30,01',3001],['20.99',2099],['0',0],['100.00',10000],[' 2,10 ',210]] as const) {
    expect(percentageBasisPoints(input)).toBe(expected)
  }
  expect(['33.33','33.33','33.34'].reduce((sum, value) => sum + percentageBasisPoints(value)!, 0)).toBe(10000)
})

it('rejects excessive precision, nonnumeric, negative and out-of-range weights', () => {
  for (const input of ['', '-1', 'NaN', 'Infinity', '1e2', '100.01', '12.345', '0,001', '1.2.3', '10000000000000000']) {
    expect(percentageBasisPoints(input)).toBeNull()
  }
})
