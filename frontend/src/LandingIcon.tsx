export function LandingIcon({ kind = 'person' }: { kind?: 'person' | 'team' | 'restaurant' | 'hotel' | 'beauty' | 'event' | 'guide' | 'music' }) {
  return <span className="lp-line-icon" aria-hidden="true"><svg viewBox="0 0 48 48" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    {kind === 'person' && <><circle cx="24" cy="15" r="7" /><path d="M10 40v-5c0-14 28-14 28 0v5Z" /></>}
    {kind === 'team' && <><circle cx="24" cy="14" r="5" /><circle cx="10" cy="20" r="4" /><circle cx="38" cy="20" r="4" /><path d="M15 38v-5c0-10 18-10 18 0v5ZM3 38v-4c0-7 8-9 12-5m30 9v-4c0-7-8-9-12-5" /></>}
    {kind === 'restaurant' && <><path d="M6 32h36M10 30a14 14 0 0 1 28 0M24 12V8m-3 0h6M6 38h36" /></>}
    {kind === 'hotel' && <><path d="M9 40V9h30v31M5 40h38M20 40V29h8v11M16 16h3m10 0h3m-16 7h3m10 0h3" /></>}
    {kind === 'beauty' && <><circle cx="13" cy="34" r="6" /><circle cx="35" cy="34" r="6" /><path d="m17 30 19-23M31 30 12 7m12 15 4-5" /></>}
    {kind === 'event' && <><path d="m24 5 5 12 13 2-10 9 3 14-11-7-11 7 3-14-10-9 13-2Z" /></>}
    {kind === 'guide' && <><path d="M24 43S10 29 10 19a14 14 0 0 1 28 0c0 10-14 24-14 24Z" /><circle cx="24" cy="19" r="5" /></>}
    {kind === 'music' && <><path d="M19 34V12l22-5v22M19 18l22-5" /><ellipse cx="12" cy="36" rx="7" ry="5" /><ellipse cx="34" cy="31" rx="7" ry="5" /></>}
  </svg></span>
}
