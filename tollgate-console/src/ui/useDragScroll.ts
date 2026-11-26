import { useEffect, useRef, useState } from 'preact/hooks';

type Grip = { x: number; y: number; left: number; top: number; moved: boolean };

const slack = 5;

export function useDragScroll(enabled: boolean) {
  const element = useRef<HTMLDivElement>(null);
  const grip = useRef<Grip | null>(null);
  const swallow = useRef(false);
  const [pannable, setPannable] = useState(false);
  const [dragging, setDragging] = useState(false);

  useEffect(() => {
    const target = element.current;
    if (!target || !enabled) {
      setPannable(false);
      return;
    }
    const measure = () =>
      setPannable(
        target.scrollWidth > target.clientWidth || target.scrollHeight > target.clientHeight,
      );
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(target);
    if (target.firstElementChild) {
      observer.observe(target.firstElementChild);
    }
    return () => observer.disconnect();
  }, [enabled]);

  function onPointerDown(event: PointerEvent) {
    swallow.current = false;
    const target = element.current;
    const hit = event.target instanceof Element ? event.target : null;
    if (!target || !hit || !pannable || event.button !== 0 || event.pointerType === 'touch') {
      return;
    }
    const scrollsItself = hit !== target && hit.scrollHeight > hit.clientHeight;
    if (scrollsItself || hit.closest('input, select, textarea')) {
      return;
    }
    grip.current = {
      x: event.clientX,
      y: event.clientY,
      left: target.scrollLeft,
      top: target.scrollTop,
      moved: false,
    };
  }

  function onPointerMove(event: PointerEvent) {
    const target = element.current;
    const from = grip.current;
    if (!target || !from) {
      return;
    }
    if ((event.buttons & 1) === 0) {
      grip.current = null;
      setDragging(false);
      return;
    }
    const dx = event.clientX - from.x;
    const dy = event.clientY - from.y;
    if (!from.moved && Math.hypot(dx, dy) < slack) {
      return;
    }
    if (!from.moved) {
      from.moved = true;
      target.setPointerCapture?.(event.pointerId);
      setDragging(true);
    }
    target.scrollLeft = from.left - dx;
    target.scrollTop = from.top - dy;
  }

  function onPointerUp() {
    swallow.current = grip.current?.moved ?? false;
    grip.current = null;
    setDragging(false);
  }

  function onClickCapture(event: MouseEvent) {
    if (swallow.current) {
      swallow.current = false;
      event.preventDefault();
      event.stopPropagation();
    }
  }

  return {
    ref: element,
    pannable,
    dragging,
    handlers: {
      onPointerDown,
      onPointerMove,
      onPointerUp,
      onPointerCancel: onPointerUp,
      onClickCapture,
    },
  };
}
