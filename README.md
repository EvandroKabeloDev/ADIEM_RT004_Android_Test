# ADIEM RT004 Android Teste — v0.1

Protótipo nativo Android (Kotlin) para substituir temporariamente o ESP32 no laboratório.

## O que faz

- Procura e conecta ao LapWiz RT004 via BLE.
- Inicializa a comunicação com os dois comandos já observados no protocolo.
- Ativa notificações GATT nas características FFF4 e FFF6.
- Reconhece notificações Type 02 (`0F .. 02 ...`) e lê o contador uint24 big-endian dos bytes 8, 9 e 10.
- Mede o intervalo entre passagens usando o relógio monotônico do Android; o contador BLE é enviado como diagnóstico, acompanhando a abordagem observada no firmware ESP32.
- Publica o mesmo tipo de JSON do ESP32 para `adiem/timing/rt004/lap` via MQTT.
- Não reenfileira automaticamente voltas que falharam, evitando que uma mensagem atrasada seja associada a um treino iniciado depois.

## Limitações deliberadas desta versão de teste

- O app precisa ficar em primeiro plano, com a tela ligada. A versão não implementa serviço BLE em segundo plano.
- O app não cria nem encerra sessão no Supabase. Abra o portal ADIEM, selecione o evento e clique em **Iniciar novo treino** antes de conectar este app ao RT004.
- O backend `SESSION-AWARE` só deve gravar as voltas quando existir sessão ativa para o endereço `A4:C1:38:3C:BE:7D`.
- Usa o broker de laboratório `tcp://91.108.125.144:1883` sem TLS/autenticação, conforme configuração de teste. Não distribuir nem usar isso como solução de produção.
- A constante do endereço BLE é a do RT004 usado no laboratório; o filtro também aceita nome começando por `LapWiz`.
- Feche o app oficial LapWiz em outros celulares antes de testar; o periférico pode não aceitar conexões BLE simultâneas.
- Ao conectar, o primeiro Type02 é apenas referência; a primeira volta publicada é calculada na próxima passagem.
- Versão experimental: compare tempos com o ESP32 / cronômetro antes de confiar nos resultados.

## Gerar APK de teste (Windows)

### Opção A — Android Studio

1. Descompacte este ZIP.
2. Abra a pasta `ADIEM_RT004_Android_Test` no Android Studio.
3. Aceite instalar Android SDK Platform 35 e as dependências solicitadas.
4. Aguarde Gradle Sync terminar.
5. Selecione **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
6. O APK debug deve aparecer em `app/build/outputs/apk/debug/app-debug.apk`.

### Opção B — terminal do Android Studio

Na raiz do projeto, execute `gradlew.bat assembleDebug`. O wrapper neste projeto baixa Gradle 8.11.1 na primeira execução, portanto é necessário acesso à internet e Java 17 ou superior.

## Instalação e teste

1. Copie `app-debug.apk` para o celular Android.
2. Permita a instalação do APK de origem externa quando o Android solicitar.
3. Abra o portal ADIEM em outro aparelho ou numa aba separada e inicie um treino com o evento/pista corretos.
4. No Android, toque em **Conectar ao broker MQTT** e confirme `MQTT conectado`.
5. Só depois toque em **Conectar ao LapWiz RT004**, conceda as permissões Bluetooth e aguarde `Aguardando passagens`.
6. Faça pelo menos duas passagens do transponder para o RT004. A primeira Type02 capturada serve de referência; a passagem seguinte gera a primeira volta.
7. Confira o log do app, logs do backend e o Live Timing.

Se a sessão não estiver ativa no portal, o backend deve rejeitar/ignorar as passagens, que é o comportamento correto nesta nova arquitetura. Se o MQTT cair no meio do teste, pare e reconecte antes de continuar; o app não faz reenvio atrasado de volta para evitar contaminar uma sessão posterior.
