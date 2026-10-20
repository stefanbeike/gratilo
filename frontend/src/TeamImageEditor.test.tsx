import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
vi.mock('./profileImage',()=>({prepareProfileImage:vi.fn()}))
import { prepareProfileImage } from './profileImage'
import { TeamImageEditor, TeamAvatar } from './TeamImageEditor'
afterEach(()=>{cleanup();vi.resetAllMocks()})
it('previews a cropped image and only publishes it after explicit confirmation',async()=>{
 vi.mocked(prepareProfileImage).mockResolvedValue('data:image/jpeg;base64,fixture');const save=vi.fn().mockResolvedValue(undefined)
 render(<TeamImageEditor name="Café Team" onSave={save}/>);fireEvent.change(screen.getByLabelText('Team-Profilbild bearbeiten'),{target:{files:[new File(['x'],'team.png',{type:'image/png'})]}})
 expect(await screen.findByRole('button',{name:'Team-Bild bestätigen'})).toBeEnabled();expect(save).not.toHaveBeenCalled();expect(screen.getByAltText('Team-Profilbild von Café Team')).toHaveAttribute('src','data:image/jpeg;base64,fixture');fireEvent.click(screen.getByRole('button',{name:'Team-Bild bestätigen'}));await waitFor(()=>expect(save).toHaveBeenCalledWith('data:image/jpeg;base64,fixture'))
})
it('reports invalid uploads and never publishes them',async()=>{vi.mocked(prepareProfileImage).mockRejectedValue(Error('Ungültiges Bild'));const save=vi.fn();render(<TeamImageEditor name="Team" onSave={save}/>);fireEvent.change(screen.getByLabelText('Team-Profilbild bearbeiten'),{target:{files:[new File(['x'],'bad.svg',{type:'image/svg+xml'})]}});expect(await screen.findByRole('alert')).toHaveTextContent('Ungültiges Bild');expect(save).not.toHaveBeenCalled()})
it('can remove a team image after confirmation without touching a personal image',async()=>{const save=vi.fn().mockResolvedValue(undefined);render(<TeamImageEditor image="old-image" name="Team" onSave={save}/>);fireEvent.click(screen.getByRole('button',{name:'Standardmotiv verwenden'}));expect(save).not.toHaveBeenCalled();expect(screen.getByRole('img',{name:'Standardmotiv für Team'})).toBeInTheDocument();fireEvent.click(screen.getByRole('button',{name:'Team-Bild bestätigen'}));await waitFor(()=>expect(save).toHaveBeenCalledWith(null))})
it('keeps teams without uploaded images visible',()=>{render(<TeamAvatar name="Bar-Team"/>);expect(screen.getByRole('img',{name:'Standardmotiv für Bar-Team'})).toBeInTheDocument()})
