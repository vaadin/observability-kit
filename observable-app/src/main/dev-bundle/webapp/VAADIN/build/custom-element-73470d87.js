/**
 * @license
 * Copyright 2017 Google LLC
 * SPDX-License-Identifier: BSD-3-Clause
 */const c=s=>t=>typeof t=="function"?((n,e)=>(customElements.define(n,e),e))(s,t):((n,e)=>{const{kind:i,elements:o}=e;return{kind:i,elements:o,finisher(m){customElements.define(n,m)}}})(s,t);export{c as e};
