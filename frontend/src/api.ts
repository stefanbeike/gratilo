export interface Session { accessToken: string; email: string; userId: string }

export async function api<T = { message: string }>(path: string, body?: unknown, token?: string, method?: 'PUT' | 'PATCH' | 'DELETE'): Promise<T> {
  let response: Response
  try {
    response = await fetch(path.startsWith('/payment-service') ? path : `/user-service${path}`, {
      method: method || (body === undefined ? 'GET' : 'POST'),
      headers: { ...(body === undefined ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    })
  } catch { throw new Error('Der Server ist nicht erreichbar. Bitte versuche es erneut.') }
  const data = await response.json().catch(() => null)
  if (!response.ok) {
    // Backend errors (especially Stripe) contain a useful, safe diagnostic.
    // Preserve it so the account page can tell the operator what to fix.
    const backendMessage = data?.detail ? `${data.message || 'Anfrage fehlgeschlagen'} ${data.detail}` : data?.message
    const error = new Error(response.status === 401 ? 'E-Mail oder Passwort ist nicht korrekt.' : backendMessage || (response.status >= 500 ? 'Das hat leider nicht geklappt. Bitte versuche es später erneut.' : 'Bitte prüfe deine Angaben. Der Link könnte ungültig oder abgelaufen sein.'))
    Object.assign(error, { code: data?.code, status: response.status })
    throw error
  }
  return data as T
}

export function dissolvePool(id: string, poolName: string, token: string) {
  return api<{ status: 'DELETED'; message: string }>(`/pools/${encodeURIComponent(id)}/dissolve`, { poolName }, token)
}
