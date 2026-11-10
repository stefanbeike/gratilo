import { expect, it } from 'vitest'
import { percentageBasisPoints, poolDistribution } from './poolPercentages'

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


it('preserves the confirmed percentage in a member-only response', () => {
  const member = {id:'membership',userId:'me',active:true,status:'ACTIVE',sharePercent:25,effectiveSharePercent:25}
  expect(poolDistribution([member], {}, false).shares.membership).toBe(25)
  expect(poolDistribution([{...member,effectiveSharePercent:0}], {}, false).shares.membership).toBe(0)
  expect(poolDistribution([{...member,effectiveSharePercent:undefined}], {}, false).shares.membership).toBe(0)
  expect(poolDistribution([{...member,status:'INVITED'}], {}, false).shares.membership).toBe(0)
})

it('normalizes full owner allocations and drafts while preserving partial confirmed shares', () => {
  const a = {id:'a',userId:'a',active:true,status:'ACTIVE',sharePercent:1,effectiveSharePercent:25}
  const b = {...a,id:'b',userId:'b',sharePercent:3,effectiveSharePercent:75}
  expect(poolDistribution([a,b]).shares).toEqual({a:25,b:75})
  expect(poolDistribution([a,b],{a:'3'}).shares).toEqual({a:50,b:50})
  expect(poolDistribution([a],{a:'100'},false)).toEqual({shares:{a:25},preview:false,invalid:false})
})
