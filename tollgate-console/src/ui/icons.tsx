import type { ComponentChildren } from 'preact';

type IconProps = { size?: number };

function icon(children: ComponentChildren, width = 1.8) {
  return function Icon({ size = 18 }: IconProps) {
    return (
      <svg
        width={size}
        height={size}
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width={width}
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        {children}
      </svg>
    );
  };
}

export const TollgateMark = icon(
  <>
    <path d="M4 21V5" />
    <path d="M4 7h16" />
    <path d="M8 7v3M12 7v3M16 7v3" />
    <path d="M2 21h5" />
  </>,
  2,
);

export const SidebarToggle = icon(
  <>
    <rect x="3" y="4" width="18" height="16" rx="2" />
    <path d="M9 4v16" />
    <path d="M15 10l-2 2 2 2" />
  </>,
);

export const Flow = icon(
  <>
    <rect x="2" y="9" width="6" height="6" rx="1.5" />
    <rect x="16" y="3" width="6" height="6" rx="1.5" />
    <rect x="16" y="15" width="6" height="6" rx="1.5" />
    <path d="M8 12h3c2 0 2-6 5-6M11 12c2 0 2 6 5 6" />
  </>,
);

export const Activity = icon(<path d="M3 12h4l3-8 4 16 3-8h4" />);

export const Budget = icon(
  <>
    <circle cx="12" cy="12" r="9" />
    <path d="M15 9.5c-.5-1-1.6-1.5-3-1.5-1.7 0-3 .8-3 2s1.3 1.7 3 2 3 .8 3 2-1.3 2-3 2c-1.4 0-2.5-.5-3-1.5M12 6v2M12 16v2" />
  </>,
);

export const Key = icon(
  <>
    <circle cx="8" cy="15" r="4" />
    <path d="M11 12l9-9M17 6l3 3M15 8l2 2" />
  </>,
);

export const Settings = icon(
  <>
    <circle cx="12" cy="12" r="3" />
    <path d="M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1L7 17M17 7l2.1-2.1" />
  </>,
);

export const Document = icon(
  <>
    <path d="M5 3h10l4 4v14H5z" />
    <path d="M9 12h6M9 16h6" />
  </>,
);

export const Cloud = icon(<path d="M7 18a4 4 0 0 1-.5-8A6 6 0 0 1 18 9a4.5 4.5 0 0 1-.5 9z" />);

export const Server = icon(
  <>
    <rect x="3" y="4" width="18" height="7" rx="1.5" />
    <rect x="3" y="13" width="18" height="7" rx="1.5" />
    <path d="M7 7.5h.01M7 16.5h.01" />
  </>,
);

export const Power = icon(
  <>
    <path d="M12 3v8" />
    <path d="M6.3 6.3a8 8 0 1 0 11.4 0" />
  </>,
  2,
);

export const ChevronsUpDown = icon(
  <>
    <path d="M7 9l5-5 5 5" />
    <path d="M7 15l5 5 5-5" />
  </>,
  2,
);

export const ChevronDown = icon(<path d="M6 9l6 6 6-6" />, 2);

export const ChevronRight = icon(<path d="M9 6l6 6-6 6" />, 2);

export const Cross = icon(<path d="M6 6l12 12M18 6L6 18" />, 2);

export const Fit = icon(<path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" />, 2);

export const Copy = icon(
  <>
    <rect x="9" y="9" width="11" height="11" rx="2" />
    <path d="M5 15V6a2 2 0 0 1 2-2h9" />
  </>,
);

export const Check = icon(<path d="M5 12.5l4.5 4.5L19 7.5" />, 2);

export const Shield = icon(
  <>
    <path d="M12 3l8 3v6c0 4.5-3.2 7.8-8 9-4.8-1.2-8-4.5-8-9V6z" />
    <path d="M9 12l2 2 4-4" />
  </>,
);

export const Chart = icon(
  <>
    <path d="M4 4v16h16" />
    <path d="M8 15l3-4 3 2 5-6" />
  </>,
);

export const Menu = icon(<path d="M4 7h16M4 12h16M4 17h16" />, 2);
