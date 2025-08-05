# DecentraCam 

A decentralized camera app that captures images, hashes them, signs with a wallet, and stores verifiable proofs on Solana.

## Setup

1. Clone the repo:
   git clone https://github.com/vibhumeh/DecentraCam-Android.git

2. Open in Android Studio

3. Install dependencies & run on emulator or device
   - make sure to have a wallet installed on emulator/device

4. Use devnet. Make sure to have airdropped SOL in testing account.

## How it Works

1. User takes a photo
2. App hashes the image using SHA-256
3. The hash is signed using their Solana wallet
4. The signature + hash is stored on-chain

## Features

- Image hash verification
- Ed25519 sign verification
- Persistent counter storage
- Verifiable proofs onchain
- Super simple UI

## Notes

- Ensure wallet app is open before signing
- Counter increments after each transaction
- Sometimes the app is removed from devnet, please make sure it is deployed as expected, else self deploy
- The app uses an embedded privatekey (for V0) to verify source. This is temporary low-security method for the POC app 
- For serious testers, please DM on @TheDecentracam [twitter](https://x.com/TheDecentracam) for keypair files.
- Store them under: app/src/main/res/raw as 'auth_privkey_temp' and public key as 'auth_pubkey_temp' in the same location.
- You may edit the smart contract to use your own custom keypair.

