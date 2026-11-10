import '@testing-library/jest-dom/vitest'

// jsdom has no modal-dialog implementation. Match browser visibility for dialog tests.
HTMLDialogElement.prototype.showModal = function () { this.setAttribute('open', '') }
HTMLDialogElement.prototype.close = function () { this.removeAttribute('open') }
