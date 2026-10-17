/* SPDX-License-Identifier: Apache-2.0 */
package vendor.evo.duckrkp;

@VintfStability
interface IDuckRkp {
    byte[] request(int backend, in byte[] payload);
}
