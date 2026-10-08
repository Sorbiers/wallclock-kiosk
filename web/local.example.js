/* This home's own starting values. Copy to local.js (which git ignores) and
   edit; they are used until something else is saved in the app's settings. */
window.KIOSK_LOCAL = {
  weather: { city: "Your city", country_code: "CA" },
  cameras: [
    { name: "Front", host: "192.168.1.10", stream: 0 },
    { name: "Back", host: "192.168.1.11", stream: 0 },
  ],
};
