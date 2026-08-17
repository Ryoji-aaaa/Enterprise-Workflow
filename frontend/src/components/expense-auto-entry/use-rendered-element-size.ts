"use client";

import { useCallback, useEffect, useRef, useState } from "react";

type RenderedElementSize = { width: number; height: number };

export function useRenderedElementSize<T extends HTMLElement>() {
  const [element, setElement] = useState<T | null>(null);
  const [size, setSize] = useState<RenderedElementSize>({ width: 0, height: 0 });
  const sizeRef = useRef(size);

  const ref = useCallback((nextElement: T | null) => {
    setElement(nextElement);
  }, []);

  useEffect(() => {
    if (!element) return;

    const update = () => {
      const { width, height } = element.getBoundingClientRect();
      if (sizeRef.current.width === width && sizeRef.current.height === height) return;
      const nextSize = { width, height };
      sizeRef.current = nextSize;
      setSize(nextSize);
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(element);
    return () => observer.disconnect();
  }, [element]);

  return { ref, size };
}
