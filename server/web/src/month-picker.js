import {dateTimeFormat} from './formatters.js';
import {getLocale} from './i18n.js';
import { t as i18nText } from "./i18n.js";import { iconButton } from './icons.js';

/** A month/year popover shared by browsers, including those without a native month input. */
export class MonthPicker {
  constructor(root, currentMonth, select) {
    this.currentMonth = currentMonth;this.select = select;
    this.button = iconButton(root.querySelector('#track-month-choose'), 'calendar', i18nText("ui.choose_month_and_year"));
    this.popup = document.createElement('div');this.popup.id = 'track-month-picker';this.popup.popover = 'auto';
    this.popup.className = 'track-month-picker shadow-lg';this.popup.setAttribute('role', 'dialog');this.popup.setAttribute('aria-label', i18nText("ui.choose_month_and_year"));
    const navigation = document.createElement('div');navigation.className = 'track-picker-year';
    this.previous = iconButton(document.createElement('button'), 'left', i18nText("ui.previous_year"));this.previous.type = 'button';
    this.next = iconButton(document.createElement('button'), 'right', i18nText("ui.next_year"));this.next.type = 'button';
    const label = document.createElement('label');label.className = 'visually-hidden';label.htmlFor = 'track-picker-year';label.textContent = i18nText("ui.year");
    this.year = document.createElement('input');this.year.id = 'track-picker-year';this.year.className = 'form-control';this.year.type = 'number';this.year.min = '1';this.year.max = '9998';this.year.required = true;
    navigation.append(this.previous, label, this.year, this.next);
    this.months = document.createElement('div');this.months.className = 'track-picker-months';
    for (let index = 1; index <= 12; index++) {
      const date = new Date(`2024-${String(index).padStart(2, '0')}-15T12:00:00Z`),button = document.createElement('button');
      button.type = 'button';button.className = 'btn btn-outline-secondary';button.dataset.month = String(index).padStart(2, '0');
      button.textContent = dateTimeFormat(getLocale(), { month: 'long', timeZone: 'UTC' }).format(date);
      button.addEventListener('click', () => {
        if (!this.year.reportValidity()) return;
        const month = String(this.year.valueAsNumber).padStart(4, '0') + '-' + button.dataset.month;
        this.close();this.select(month);this.button.focus();
      });
      this.months.append(button);
    }
    this.previous.addEventListener('click', () => this.shiftYear(-1));this.next.addEventListener('click', () => this.shiftYear(1));
    this.year.addEventListener('input', () => this.paint());
    this.popup.append(navigation, this.months);root.append(this.popup);
    this.popup.addEventListener('beforetoggle', (event) => {
      this.button.setAttribute('aria-expanded', String(event.newState === 'open'));
      if (event.newState === 'open') {
        this.year.value = this.currentMonth().slice(0, 4);this.paint();
        requestAnimationFrame(() => {
          if (!this.popup.matches(':popover-open')) return;
          this.position();this.months.querySelector('[aria-pressed="true"]')?.focus();
        });
      }
    });
    window.addEventListener('resize', () => this.position());document.addEventListener('scroll', () => this.position(), true);
  }
  paint() {
    const valid = this.year.validity.valid,year = this.year.valueAsNumber;
    this.previous.disabled = !valid || year <= 1;this.next.disabled = !valid || year >= 9998;
    for (const button of this.months.children) {
      button.disabled = !valid;
      const selected = String(year).padStart(4, '0') + '-' + button.dataset.month === this.currentMonth();
      button.setAttribute('aria-pressed', String(selected));button.classList.toggle('btn-primary', selected);button.classList.toggle('btn-outline-secondary', !selected);
    }
  }
  shiftYear(offset) {if (this.year.validity.valid) {this.year.value = String(this.year.valueAsNumber + offset);this.paint();}}
  position() {
    if (!this.popup.matches(':popover-open')) return;
    const rect = this.button.getBoundingClientRect();
    this.popup.style.left = Math.max(8, Math.min(rect.left, innerWidth - this.popup.offsetWidth - 8)) + 'px';
    this.popup.style.top = Math.max(8, Math.min(rect.bottom + 8, innerHeight - this.popup.offsetHeight - 8)) + 'px';
  }
  close() {if (this.popup.matches(':popover-open')) this.popup.hidePopover();}
}
