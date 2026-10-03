package com.discord.socialsdk.rpc;

public interface IDiscordRpcService extends android.os.IInterface {
    public static final String DESCRIPTOR = "com.discord.socialsdk.rpc.IDiscordRpcService";

    public IDiscordRpcConnection connect(long applicationId, String version, IDiscordRpcCallback callback) throws android.os.RemoteException;

    public static abstract class Stub extends android.os.Binder implements IDiscordRpcService {
        static final int TRANSACTION_connect = android.os.IBinder.FIRST_CALL_TRANSACTION + 0;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static IDiscordRpcService asInterface(android.os.IBinder obj) {
            if (obj == null) return null;
            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
            if (iin instanceof IDiscordRpcService) return (IDiscordRpcService) iin;
            return new Proxy(obj);
        }

        @Override
        public android.os.IBinder asBinder() {
            return this;
        }

        @Override
        public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException {
            if (code == TRANSACTION_connect) {
                data.enforceInterface(DESCRIPTOR);
                long _arg0 = data.readLong();
                String _arg1 = data.readString();
                IDiscordRpcCallback _arg2 = IDiscordRpcCallback.Stub.asInterface(data.readStrongBinder());
                IDiscordRpcConnection _result = this.connect(_arg0, _arg1, _arg2);
                reply.writeNoException();
                reply.writeStrongBinder((_result != null) ? _result.asBinder() : null);
                return true;
            } else if (code == INTERFACE_TRANSACTION) {
                reply.writeString(DESCRIPTOR);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }

        private static class Proxy implements IDiscordRpcService {
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
            public IDiscordRpcConnection connect(long applicationId, String version, IDiscordRpcCallback callback) throws android.os.RemoteException {
                android.os.Parcel _data = android.os.Parcel.obtain();
                android.os.Parcel _reply = android.os.Parcel.obtain();
                IDiscordRpcConnection _result;
                try {
                    _data.writeInterfaceToken(DESCRIPTOR);
                    _data.writeLong(applicationId);
                    _data.writeString(version);
                    _data.writeStrongBinder((callback != null) ? callback.asBinder() : null);
                    mRemote.transact(TRANSACTION_connect, _data, _reply, 0);
                    _reply.readException();
                    _result = IDiscordRpcConnection.Stub.asInterface(_reply.readStrongBinder());
                } finally {
                    _reply.recycle();
                    _data.recycle();
                }
                return _result;
            }
        }
    }
}
