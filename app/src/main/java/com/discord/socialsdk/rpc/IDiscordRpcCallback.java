package com.discord.socialsdk.rpc;

public interface IDiscordRpcCallback extends android.os.IInterface {
    public static final String DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcCallback";

    public void onFrame(String frame) throws android.os.RemoteException;
    public void onClose(int code, String message) throws android.os.RemoteException;

    public static abstract class Stub extends android.os.Binder implements IDiscordRpcCallback {
        static final int TRANSACTION_onFrame = android.os.IBinder.FIRST_CALL_TRANSACTION + 0;
        static final int TRANSACTION_onClose = android.os.IBinder.FIRST_CALL_TRANSACTION + 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDiscordRpcCallback asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDiscordRpcCallback) return (IDiscordRpcCallback) iin;
            return new Proxy(obj);
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code == TRANSACTION_onFrame) {
                data.enforceInterface(DESCRIPTOR);
                String _arg0 = data.readString();
                this.onFrame(_arg0);
                reply.writeNoException();
                return true;
            } else if (code == TRANSACTION_onClose) {
                data.enforceInterface(DESCRIPTOR);
                int _arg0 = data.readInt();
                String _arg1 = data.readString();
                this.onClose(_arg0, _arg1);
                reply.writeNoException();
                return true;
            } else if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDiscordRpcCallback {
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
            public void onFrame(String frame) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeString(frame);
                    mRemote.transact(TRANSACTION_onFrame, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }

            @Override
            public void onClose(int code, String message) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeInt(code);
                    _data.writeString(message);
                    mRemote.transact(TRANSACTION_onClose, _data, _reply, 0);
                    _reply.readException();
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
            }
        }
    }
}
