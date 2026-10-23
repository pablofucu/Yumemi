package com.pablo.yumemi

/** Shaped like the first real feed Hermes published (vuelos, 18 oct 2026). */
object FeedSamples {
    val INDEX = """
        {"schema":1,"updated_at":"2026-10-18T21:25:01+02:00","feeds":[
          {"tag":"vuelos","title":"Vuelos","emoji":"✈️","job_id":"dbca38163700"},
          {"tag":"../etc","title":"Malo"},
          {"tag":"luces","title":"Luces","emoji":"💡"}
        ]}
    """.trimIndent()

    val VUELOS = """
        {"schema":1,"tag":"vuelos","job_id":"dbca38163700","run_at":"2026-10-18T21:25:01+02:00","status":"ok",
         "summary":"Mejor: SVQ→LON 112 € (5 noches, 15 feb → 20 feb) · -18 € en 7 días",
         "view":[
          {"type":"stat","label":"Mejor precio","value":"112 €","delta":"-18 €"},
          {"type":"stat","label":"Con los dos vuelos de mañana","value":"169 €","delta":"4n · 17 feb → 21 feb"},
          {"type":"kv","items":[["Ruta","SVQ→LON"],["Fechas","lun 15 feb → sáb 20 feb"],["Noches","5"],["Ida","07:10 · Vueling"],["Vuelta","17:15 · Ryanair"],["Consultado","18/10/2026"]]},
          {"type":"table","columns":["Salida","Regreso","Noches","Precio","Aerolínea"],"rows":[["15 feb","20 feb","5","112 €","Vueling"],["16 feb","20 feb","4","122 €","easyJet"],["16 feb","21 feb","5","131 €","easyJet"]]},
          {"type":"series","label":"Mínimo diario ida+vuelta","unit":"€","points":[["10 oct",130],["11 oct",128],["12 oct",131],["13 oct",125],["14 oct",124],["15 oct",120],["16 oct",118],["17 oct",115],["18 oct",112]]},
          {"type":"text","text":"Compra ya: está en la banda baja de Google para esa ruta y fecha. Faltan 120 días para salir."},
          {"type":"text","text":"Banda habitual de Google: 95 €–445 € (hoy en el 5%)."},
          {"type":"link","label":"Ver en Google Flights","url":"https://www.google.com/travel/flights/search?tfs=abc"},
          {"type":"hologram","foo":1}
         ],
         "data":{"best_per_nights":{"4":122,"5":112}},"error":null}
    """.trimIndent().replace("\n", "")

    /** A different automation, to check the generic blocks hold up beyond flights. */
    val LUCES = """
        {"schema":1,"tag":"luces","job_id":"aa11","run_at":"2026-10-19T07:00:00+02:00","status":"ok",
         "summary":"Mañana la luz baja: media 0,14 €/kWh, la hora más barata es a las 04:00",
         "view":[
          {"type":"stat","label":"Precio medio","value":"0,14 €/kWh","delta":"-6 % vs hoy"},
          {"type":"stat","label":"Hora más barata","value":"04:00","delta":"0,085 €/kWh"},
          {"type":"stat","label":"Hora más cara","value":"19:00","delta":"0,248 €/kWh"},
          {"type":"series","label":"Precio por hora (PVPC)","unit":"€/kWh","points":[["00:00",0.112],["01:00",0.098],["02:00",0.091],["03:00",0.087],["04:00",0.085],["05:00",0.089],["06:00",0.121],["07:00",0.164],["08:00",0.198],["09:00",0.176],["10:00",0.142],["11:00",0.118],["12:00",0.104],["13:00",0.097],["14:00",0.101],["15:00",0.115],["16:00",0.139],["17:00",0.172],["18:00",0.221],["19:00",0.248],["20:00",0.236],["21:00",0.201],["22:00",0.163],["23:00",0.131]]},
          {"type":"table","columns":["Tramo","Desde","Hasta","Media","Mín.","Máx.","Recomendación"],"rows":[["Valle","00:00","08:00","0,098","0,085","0,121","Lavadora y lavavajillas"],["Llano","08:00","10:00","0,187","0,176","0,198","Evitar si puedes"],["Punta","18:00","22:00","0,227","0,201","0,248","Nada de horno ni secadora"]]},
          {"type":"kv","items":[["Fuente","REE · PVPC 2.0TD"],["Zona","Península"],["Actualizado","19/10/2026 07:00"],["Notas","Los precios incluyen peajes y cargos, pero no el impuesto eléctrico ni el IVA"]]},
          {"type":"text","text":"Pon la lavadora antes de las 8: ahorras unos 0,40 € frente a ponerla por la tarde. El jueves se espera viento y precios todavía más bajos."},
          {"type":"link","label":"Ver en REE","url":"https://www.ree.es/es/datos/mercados"}
         ],"error":null}
    """.trimIndent().replace("\n", "")
}
