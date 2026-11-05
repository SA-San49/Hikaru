const menuButton = document.querySelector(".menu-toggle");
const navigation = document.querySelector(".site-nav");

if (menuButton && navigation) {
  const closeMenu = () => {
    menuButton.setAttribute("aria-expanded", "false");
    menuButton.setAttribute("aria-label", "Open navigation");
    navigation.classList.remove("is-open");
  };

  menuButton.addEventListener("click", () => {
    const isOpen = menuButton.getAttribute("aria-expanded") === "true";
    menuButton.setAttribute("aria-expanded", String(!isOpen));
    menuButton.setAttribute("aria-label", isOpen ? "Open navigation" : "Close navigation");
    navigation.classList.toggle("is-open", !isOpen);
  });

  navigation.querySelectorAll("a").forEach((link) => link.addEventListener("click", closeMenu));
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeMenu();
  });
  document.addEventListener("click", (event) => {
    if (!navigation.contains(event.target) && !menuButton.contains(event.target)) closeMenu();
  });
}

const gallery = document.querySelector("[data-gallery]");
if (gallery) {
  const slides = [...gallery.querySelectorAll(".gallery-data [data-image]")].map((item) => ({
    image: item.dataset.image,
    alt: item.dataset.alt,
    kicker: item.dataset.kicker,
    title: item.dataset.title,
    description: item.dataset.description,
    label: item.dataset.label,
  }));
  const stage = gallery.querySelector(".gallery-stage");
  const image = gallery.querySelector("[data-gallery-image]");
  const title = gallery.querySelector("[data-gallery-title]");
  const kicker = gallery.querySelector("[data-gallery-kicker]");
  const description = gallery.querySelector("[data-gallery-description]");
  const count = gallery.querySelector("[data-gallery-count]");
  const zoomOpenButtons = gallery.querySelectorAll("[data-open-zoom]");
  const dots = [...gallery.querySelectorAll("[data-gallery-dot]")];
  const playButton = gallery.querySelector("[data-gallery-toggle]");
  const dialog = document.querySelector("[data-gallery-dialog]");
  const zoomImage = dialog?.querySelector("[data-zoom-image]");
  const zoomLabel = dialog?.querySelector("[data-zoom-label]");
  const zoomValue = dialog?.querySelector("[data-zoom-value]");
  const zoomCanvas = dialog?.querySelector("[data-zoom-canvas]");
  let currentIndex = 0;
  let timer = 0;
  let manuallyPaused = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  let resumeOverride = false;
  let hovered = false;
  let focused = false;
  let zoom = 1;
  let pointerStart = null;
  let fadeTimer = 0;

  const pauseTimer = () => {
    window.clearTimeout(timer);
    timer = 0;
  };

  const canAutoplay = () => !manuallyPaused && ((!hovered && !focused) || resumeOverride) && !document.hidden && !dialog?.open;

  const scheduleNext = () => {
    pauseTimer();
    if (!canAutoplay() || slides.length < 2) return;
    timer = window.setTimeout(() => showSlide(currentIndex + 1, { automatic: true }), 3000);
  };

  const updatePlayButton = () => {
    const label = manuallyPaused ? "Play automatic slideshow" : "Pause automatic slideshow";
    playButton.setAttribute("aria-label", label);
    playButton.title = label;
  };

  const preload = (index) => {
    const nextImage = new Image();
    nextImage.src = slides[(index + slides.length) % slides.length].image;
  };

  function showSlide(index, { automatic = false } = {}) {
    if (!slides.length) return;
    if (!automatic) {
      manuallyPaused = true;
      resumeOverride = false;
      pauseTimer();
      updatePlayButton();
    }
    currentIndex = (index + slides.length) % slides.length;
    const slide = slides[currentIndex];
    image.classList.add("is-changing");
    window.clearTimeout(fadeTimer);
    fadeTimer = window.setTimeout(() => {
      image.src = slide.image;
      image.alt = slide.alt;
      title.textContent = slide.title;
      kicker.textContent = slide.kicker;
      description.textContent = slide.description;
      count.innerHTML = `${String(currentIndex + 1).padStart(2, "0")} <i>/</i> ${String(slides.length).padStart(2, "0")}`;
      count.setAttribute("aria-live", automatic ? "off" : "polite");
      stage.setAttribute("aria-label", `${currentIndex + 1} of ${slides.length}: ${slide.label}`);
      gallery.querySelector("[data-open-zoom].gallery-screen")?.setAttribute("aria-label", `Enlarge the ${slide.label} screenshot`);
      zoomOpenButtons.forEach((button) => {
        if (button.classList.contains("gallery-zoom-link")) {
          button.setAttribute("aria-label", `Open the ${slide.label} screenshot in the zoom viewer`);
        }
      });
      dots.forEach((dot, dotIndex) => {
        const active = dotIndex === currentIndex;
        dot.classList.toggle("is-active", active);
        dot.setAttribute("aria-pressed", String(active));
      });
      gallery.querySelector(".gallery-copy").setAttribute("aria-live", automatic ? "off" : "polite");
      image.classList.remove("is-changing");
      preload(currentIndex + 1);
      scheduleNext();
    }, 130);
  }

  const applyZoomSize = () => {
    if (!zoomImage?.naturalWidth || !zoomCanvas) return;
    const aspect = zoomImage.naturalWidth / zoomImage.naturalHeight;
    const fitWidth = Math.min(zoomCanvas.clientWidth, zoomCanvas.clientHeight * aspect);
    zoomImage.style.width = `${Math.max(1, fitWidth * zoom)}px`;
  };

  const setZoom = (nextZoom) => {
    zoom = Math.min(3, Math.max(0.5, Math.round(nextZoom * 4) / 4));
    if (zoomImage.naturalWidth) applyZoomSize();
    else zoomImage.style.width = `${zoom * 100}%`;
    zoomValue.value = `${Math.round(zoom * 100)}%`;
    zoomValue.textContent = `${Math.round(zoom * 100)}%`;
  };

  const openZoom = () => {
    if (!dialog || !zoomImage) return;
    manuallyPaused = true;
    resumeOverride = false;
    pauseTimer();
    updatePlayButton();
    const slide = slides[currentIndex];
    zoomImage.src = slide.image;
    zoomImage.alt = slide.alt;
    zoomLabel.textContent = `${slide.label} · Hikaru app screenshot`;
    setZoom(1);
    if (!dialog.open) dialog.showModal();
    dialog.querySelector("[data-close-zoom]").focus();
    window.requestAnimationFrame(applyZoomSize);
  };

  gallery.querySelector("[data-gallery-previous]").addEventListener("click", () => showSlide(currentIndex - 1));
  gallery.querySelector("[data-gallery-next]").addEventListener("click", () => showSlide(currentIndex + 1));
  dots.forEach((dot) => dot.addEventListener("click", () => showSlide(Number(dot.dataset.galleryDot))));
  zoomOpenButtons.forEach((button) => button.addEventListener("click", openZoom));

  playButton.addEventListener("click", () => {
    manuallyPaused = !manuallyPaused;
    resumeOverride = !manuallyPaused;
    updatePlayButton();
    if (manuallyPaused) pauseTimer();
    else scheduleNext();
  });

  gallery.addEventListener("pointerenter", (event) => {
    if (event.pointerType === "mouse") {
      hovered = true;
      pauseTimer();
    }
  });
  gallery.addEventListener("pointerleave", (event) => {
    if (event.pointerType === "mouse") {
      hovered = false;
      scheduleNext();
    }
  });
  gallery.addEventListener("focusin", () => {
    focused = true;
    pauseTimer();
  });
  gallery.addEventListener("focusout", (event) => {
    if (!gallery.contains(event.relatedTarget)) {
      focused = false;
      scheduleNext();
    }
  });
  gallery.addEventListener("keydown", (event) => {
    if (event.key === "ArrowLeft") {
      event.preventDefault();
      showSlide(currentIndex - 1);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      showSlide(currentIndex + 1);
    }
  });
  stage.addEventListener("pointerdown", (event) => {
    if (event.pointerType === "touch") pointerStart = { x: event.clientX, y: event.clientY };
  });
  stage.addEventListener("pointerup", (event) => {
    if (!pointerStart) return;
    const deltaX = event.clientX - pointerStart.x;
    const deltaY = event.clientY - pointerStart.y;
    pointerStart = null;
    if (Math.abs(deltaX) > 45 && Math.abs(deltaX) > Math.abs(deltaY)) {
      showSlide(currentIndex + (deltaX < 0 ? 1 : -1));
    }
  });
  stage.addEventListener("pointercancel", () => { pointerStart = null; });

  dialog?.querySelector("[data-zoom-in]").addEventListener("click", () => setZoom(zoom + 0.25));
  dialog?.querySelector("[data-zoom-out]").addEventListener("click", () => setZoom(zoom - 0.25));
  dialog?.querySelector("[data-zoom-reset]").addEventListener("click", () => setZoom(1));
  dialog?.querySelector("[data-close-zoom]").addEventListener("click", () => dialog.close());
  dialog?.addEventListener("click", (event) => {
    if (event.target === dialog) dialog.close();
  });
  zoomCanvas?.addEventListener("wheel", (event) => {
    if (!event.ctrlKey && !event.metaKey) return;
    event.preventDefault();
    setZoom(zoom + (event.deltaY < 0 ? 0.25 : -0.25));
  }, { passive: false });
  zoomImage?.addEventListener("dblclick", () => setZoom(zoom > 1 ? 1 : 2));
  zoomImage?.addEventListener("load", applyZoomSize);
  window.addEventListener("resize", () => {
    if (dialog?.open) applyZoomSize();
  });

  document.addEventListener("visibilitychange", scheduleNext);
  window.matchMedia("(prefers-reduced-motion: reduce)").addEventListener("change", (event) => {
    if (event.matches) {
      manuallyPaused = true;
      resumeOverride = false;
      pauseTimer();
      updatePlayButton();
    }
  });

  updatePlayButton();
  preload(1);
  scheduleNext();
}
