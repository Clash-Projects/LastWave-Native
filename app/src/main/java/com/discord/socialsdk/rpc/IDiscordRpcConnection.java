package com.discord.socialsdk.rpc;

public interface IDiscordRpcConnection extends android.os.IInterface {
    public static final String DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcConnection";

    public void sendFrame(String frame) throws android.os.RemoteException;
    public void disconnect() throws android.os.RemoteException;

    public static abstract class Stub extends android.os.Binder implements IDiscordRpcConnection {
        static final int TRANSACTION_sendFrame = android.os.IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_disconnect = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDiscordRpcConnection asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDiscordRpcConnection) return (IDiscordRpcConnection) iin;
            return new Proxy(obj);
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code == TRANSACTION_sendFrame) {
                data.enforceInterface(DESCRIPTOR);
                String _arg0 = data.readString();
                this.sendFrame(_arg0);
                reply.writeNoException();
                return true;
            } else if (code == TRANSACTION_disconnect) {
                data.enforceInterface(DESCRIPTOR);
                this.disconnect();
                reply.writeNoException();
                return true;
            } else if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDiscordRpcConnection {
            private final android.os.IBinder mRemote;

            Proxy(android.os.IBinder remote) {
                mRemote = remote;
            }

            @Override
            public android.os.IBinder asBinder() {
                return mRemote;
            }

            public String getInterfaceDescriptor() {
                return DESCRIPTOR;
            }

            @Override
            public void sendFrame(String frame) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(frame);
                    mRemote.transact(TRANSACTION_sendFrame, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void disconnect() throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    mRemote.transact(TRANSACTION_disconnect, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
