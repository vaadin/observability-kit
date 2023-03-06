import {html, LitElement, nothing} from 'lit';
import {customElement} from 'lit/decorators.js';

@customElement('problematic-component')
export class ProblematicComponent extends LitElement {

    render() {
        return html`
            <button @click="${this._throwError}">Throw error</button>
            <button @click="${this._slowTask}">Slow task</button>
        `;
    }

    _throwError() {
        throw new Error('A client side error');
    }

    _slowTask() {
        console.log("Blocking task started");
        let counter = 100000000;
        while (counter-- > 0) {
            if (counter % 1000 == 0) {
                console.log("Blocking Task: still doing something useless...", counter);
            }
        }
        console.log("Blocking Task completed");
    }

}

