package com.agvtronic.pickvoice.dat.mockdevice

import android.content.Context

/**
 * Variante **release** de [prepararDispositivoSimulado]: não faz nada.
 *
 * Em release o dispositivo é o óculos físico, descoberto pelo `AutoDeviceSelector` como
 * qualquer outro dispositivo pareado — não há nada a simular. A contraparte em
 * `app/src/debug/` tem a mesma assinatura e sobe o óculos do MockDeviceKit.
 *
 * Existir como no-op, em vez de um `if (BuildConfig.DEBUG)` dentro do controlador, é o que
 * mantém o `mwdat-mockdevice` fora do APK de produção: a dependência é `debugImplementation`,
 * então nenhum símbolo dela pode ser referenciado a partir daqui.
 */
@Suppress("UNUSED_PARAMETER")
fun prepararDispositivoSimulado(context: Context) {
  // Intencionalmente vazio — ver KDoc.
}
