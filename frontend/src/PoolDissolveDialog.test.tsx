import { fireEvent, render, screen, waitFor, cleanup } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { PoolDissolveDialog } from './PoolDissolveDialog'
afterEach(()=>{cleanup();vi.unstubAllGlobals()})
const pool={id:'pool-id',name:'Mein Team'}
it('requires the exact pool name and submits only this pool once while pending',async()=>{
  let resolve!:(value:unknown)=>void
  const fetch=vi.fn(()=>new Promise(r=>{resolve=r}));vi.stubGlobal('fetch',fetch)
  const done=vi.fn();render(<PoolDissolveDialog pool={pool} token="jwt" onClose={vi.fn()} onDissolved={done} onClosing={vi.fn()}/>)
  expect(screen.getByRole('dialog')).toBeVisible()
  const submit=screen.getByRole('button',{name:'Pool endgültig auflösen'});expect(submit).toBeDisabled()
  fireEvent.change(screen.getByLabelText(/Gib zur Bestätigung/),{target:{value:'Mein'}});expect(submit).toBeDisabled()
  fireEvent.change(screen.getByLabelText(/Gib zur Bestätigung/),{target:{value:pool.name}});fireEvent.click(submit)
  expect(screen.getByRole('button',{name:'Pool wird aufgelöst …'})).toBeDisabled()
  expect(fetch).toHaveBeenCalledOnce();expect(fetch).toHaveBeenCalledWith('/user-service/pools/pool-id/dissolve',expect.objectContaining({method:'POST',body:JSON.stringify({poolName:pool.name})}))
  resolve({ok:true,json:async()=>({status:'DELETED'})});await waitFor(()=>expect(done).toHaveBeenCalledWith(pool.id))
})
it('keeps the dialog and retry available for outstanding finances',async()=>{
  vi.stubGlobal('fetch',vi.fn(async()=>({ok:false,status:409,json:async()=>({code:'POOL_FINANCE_PENDING',message:'Offene Transfers müssen abgeschlossen werden.'})})))
  const closing=vi.fn(),done=vi.fn();render(<PoolDissolveDialog pool={pool} token="jwt" onClose={vi.fn()} onDissolved={done} onClosing={closing}/>)
  fireEvent.change(screen.getByLabelText(/Gib zur Bestätigung/),{target:{value:pool.name}});fireEvent.click(screen.getByRole('button',{name:'Pool endgültig auflösen'}))
  expect(await screen.findByRole('alert')).toHaveTextContent('Offene Transfers');expect(done).not.toHaveBeenCalled();expect(closing).toHaveBeenCalledWith(pool.id)
  expect(screen.getByRole('button',{name:'Pool endgültig auflösen'})).toBeEnabled()
})
