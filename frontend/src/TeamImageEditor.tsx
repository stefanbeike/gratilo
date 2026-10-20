import { useState } from 'react'
import { prepareProfileImage } from './profileImage'
export function TeamAvatar({ image, name }: { image?: string | null; name: string }) {
  return image ? <img className="team-avatar" src={image} alt={`Team-Profilbild von ${name}`} /> : <span className="team-avatar team-avatar-default" role="img" aria-label={`Standardmotiv für ${name}`}><span aria-hidden="true">♧</span></span>
}
export function teamPreset(kind: 'team' | 'cafe' | 'bar') {
  const canvas=document.createElement('canvas');canvas.width=256;canvas.height=256;const context=canvas.getContext('2d');if(!context)throw new Error('Das Standardmotiv konnte nicht vorbereitet werden.')
  const palette={team:['#e4f1eb','#357664','TEAM'],cafe:['#f5ecdf','#8a6744','CAFÉ'],bar:['#e8edf8','#4a6295','BAR']}[kind];context.fillStyle=palette[0];context.fillRect(0,0,256,256);context.strokeStyle=palette[1];context.lineWidth=3;context.beginPath();context.arc(128,128,92,0,Math.PI*2);context.stroke();context.fillStyle=palette[1];context.font='bold 34px sans-serif';context.textAlign='center';context.textBaseline='middle';context.fillText(palette[2],128,128);return canvas.toDataURL('image/jpeg',.85)
}
export function TeamImageEditor({ image, name, onSave }: { image?: string | null; name: string; onSave: (image: string | null) => Promise<void> }) {
  const [preview, setPreview] = useState<string | null | undefined>(image)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const changed = preview !== image
  async function save() { setBusy(true);setError('');try { await onSave(preview || null) } catch (cause) {setError(cause instanceof Error ? cause.message : 'Das Team-Bild konnte nicht gespeichert werden.')}finally{setBusy(false)} }
  return <div className="team-image-editor"><TeamAvatar image={preview} name={name}/><div><label htmlFor="team-image">Team-Profilbild bearbeiten</label><input id="team-image" type="file" accept="image/jpeg,image/png,image/webp" disabled={busy} onChange={async event=>{const file=event.target.files?.[0];event.target.value='';if(!file)return;setBusy(true);setError('');try{setPreview(await prepareProfileImage(file))}catch(cause){setError(cause instanceof Error?cause.message:'Bild konnte nicht geladen werden.')}finally{setBusy(false)}}}/><p className="muted">JPG, PNG oder WebP · bis 5 MB · quadratischer Mittelpunkt-Zuschnitt. Erst nach Bestätigung öffentlich sichtbar.</p><div className="team-motifs" role="group" aria-label="Standardmotive"><span>Eigenes Motiv oder eine Vorlage:</span>{(['team','cafe','bar'] as const).map(kind=><button className="outline-button" key={kind} disabled={busy} onClick={()=>{try{setPreview(teamPreset(kind));setError('')}catch(cause){setError(cause instanceof Error?cause.message:'Motiv nicht verfügbar.')}}}>{kind==='team'?'Team':kind==='cafe'?'Café':'Bar'}</button>)}</div><div className="team-image-actions"><button className="text-button" disabled={busy || !preview} onClick={()=>setPreview(null)}>Standardmotiv verwenden</button>{changed && <><button className="outline-button" disabled={busy} onClick={()=>{setPreview(image);setError('')}}>Verwerfen</button><button className="solid-button" disabled={busy} onClick={save}>Team-Bild bestätigen</button></>}</div>{busy && <p role="status">Bild wird verarbeitet …</p>}{error && <p role="alert" className="message error">{error}</p>}</div></div>
}
