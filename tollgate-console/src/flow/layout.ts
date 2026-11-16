export type Rect = { x: number; y: number; w: number; h: number };

export type Counts = { tenants: number; checks: number; providers: number };

export type Layout = {
  band: 'wide' | 'medium';
  width: number;
  height: number;
  tenants: Rect[];
  policy: Rect;
  pills: Rect[];
  providers: Rect[];
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

const tenantHeight = 72;
const providerHeight = 96;
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
  const band = width >= 1200 ? 'wide' : 'medium';
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

  const firstTenant = Math.max(sizes.pad, middle - tenantHeight / 2);
  const tenants = Array.from({ length: counts.tenants }, (_, index) => ({
    x: tenantX,
    y: firstTenant + index * (tenantHeight + nodeGap),
    w: sizes.tenant,
    h: tenantHeight,
  }));

  const firstProvider = Math.max(
    sizes.pad,
    middle - stack(counts.providers, providerHeight, nodeGap) / 2,
  );
  const providers = Array.from({ length: counts.providers }, (_, index) => ({
    x: providerX,
    y: firstProvider + index * (providerHeight + nodeGap),
    w: sizes.provider,
    h: providerHeight,
  }));

  const bottom = Math.max(
    policy.y + policy.h,
    ...tenants.map((rect) => rect.y + rect.h),
    ...providers.map((rect) => rect.y + rect.h),
  );
  return { band, width, height: bottom + sizes.pad, tenants, policy, pills, providers };
}
