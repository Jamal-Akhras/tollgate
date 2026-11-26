export type Rect = { x: number; y: number; w: number; h: number };

export type Counts = { tenants: number; checks: number; providers: number };

export type Layout = {
  band: 'wide' | 'medium';
  width: number;
  height: number;
  tenants: Rect[];
  filter: Rect | null;
  policy: Rect;
  pills: Rect[];
  providers: Rect[];
  refused: Rect;
};

type Band = {
  tenant: number;
  policy: number;
  provider: number;
  pad: number;
  minGap: number;
  maxGap: number;
};

const bands: Record<Layout['band'], Band> = {
  wide: { tenant: 240, policy: 320, provider: 260, pad: 24, minGap: 64, maxGap: 120 },
  medium: { tenant: 200, policy: 240, provider: 200, pad: 16, minGap: 48, maxGap: 120 },
};

const visibleTenants = 8;
const filterHeight = 36;
const tenantHeight = 72;
const providerHeight = 96;
const refusedHeight = 72;
const refusedGap = 28;
const nodeGap = 16;
const pillHeight = 36;
const pillGap = 10;
const boxTop = 44;
const boxBottom = 16;
const boxSide = 16;

function stack(count: number, height: number, gap: number): number {
  return count === 0 ? 0 : count * height + (count - 1) * gap;
}

export function layout(width: number, counts: Counts): Layout | null {
  if (width < 800) {
    return null;
  }
  const band = width >= 1040 ? 'wide' : 'medium';
  const sizes = bands[band];
  const columns = sizes.tenant + sizes.policy + sizes.provider;
  const gap = Math.min(
    sizes.maxGap,
    Math.max(sizes.minGap, (width - 2 * sizes.pad - columns) / 2),
  );
  const left = Math.max(sizes.pad, (width - columns - 2 * gap) / 2);
  const tenantX = left;
  const policyX = tenantX + sizes.tenant + gap;
  const providerX = policyX + sizes.policy + gap;

  const boxHeight = boxTop + stack(counts.checks, pillHeight, pillGap) + boxBottom;
  const policy = { x: policyX, y: sizes.pad, w: sizes.policy, h: boxHeight };
  const middle = policy.y + boxHeight / 2;

  const pills = Array.from({ length: counts.checks }, (_, index) => ({
    x: policyX + boxSide,
    y: policy.y + boxTop + index * (pillHeight + pillGap),
    w: sizes.policy - 2 * boxSide,
    h: pillHeight,
  }));

  const scrolls = counts.tenants > visibleTenants;
  const filterRoom = scrolls ? filterHeight + nodeGap / 2 : 0;
  const shown = Math.min(counts.tenants, visibleTenants);
  const firstTenant = Math.max(
    sizes.pad + filterRoom,
    middle - stack(shown, tenantHeight, nodeGap) / 2,
  );
  const tenants = Array.from({ length: shown }, (_, index) => ({
    x: tenantX,
    y: firstTenant + index * (tenantHeight + nodeGap),
    w: sizes.tenant,
    h: tenantHeight,
  }));

  const filter = scrolls
    ? { x: tenantX, y: firstTenant - filterRoom, w: sizes.tenant, h: filterHeight }
    : null;

  const providerStack = stack(counts.providers, providerHeight, nodeGap);
  const above = providerStack === 0 ? 0 : providerStack + refusedGap;
  const firstProvider = Math.max(sizes.pad, middle - (above + refusedHeight) / 2);
  const providers = Array.from({ length: counts.providers }, (_, index) => ({
    x: providerX,
    y: firstProvider + index * (providerHeight + nodeGap),
    w: sizes.provider,
    h: providerHeight,
  }));
  const refused = {
    x: providerX,
    y: firstProvider + above,
    w: sizes.provider,
    h: refusedHeight,
  };

  const bottom = Math.max(
    policy.y + policy.h,
    refused.y + refused.h,
    ...tenants.map((rect) => rect.y + rect.h),
  );
  return {
    band,
    width,
    height: bottom + sizes.pad,
    tenants,
    filter,
    policy,
    pills,
    providers,
    refused,
  };
}
